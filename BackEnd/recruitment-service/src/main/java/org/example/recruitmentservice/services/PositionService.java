package org.example.recruitmentservice.services;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.dto.response.PageResponse;
import org.example.commonlibrary.exception.CustomException;
import org.example.commonlibrary.utils.PageUtil;
import org.example.recruitmentservice.client.LlamaParseClient;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.JDChunkPayload;
import org.example.recruitmentservice.dto.request.JDChunkedEvent;
import org.example.recruitmentservice.dto.request.PositionsRequest;
import org.example.recruitmentservice.dto.response.DriveFileInfo;
import org.example.recruitmentservice.dto.response.PositionsResponse;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.example.recruitmentservice.services.chunking.JDChunkingService;
import org.example.recruitmentservice.models.enums.JDStatus;
import org.example.recruitmentservice.models.enums.BatchType;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository positionRepository;
    private final LlamaParseClient llamaParseClient;
    private final StorageService storageService;
    private final CandidateCVRepository candidateCVRepository;
    private final RabbitTemplate rabbitTemplate;
    private final RestTemplate restTemplate;
    private final JDChunkingService jdChunkingService;
    private final ProcessingBatchService processingBatchService;

    @Value("${EMBEDDING_SERVICE_URL}")
    private String embeddingServiceUrl;

    @Transactional
    public ApiResponse<PositionsResponse> createPosition(PositionsRequest positionsRequest, HttpServletRequest request) {
        // Check duplicate
        Optional<Positions> existing = positionRepository.findByNameAndLanguageAndLevel(
                positionsRequest.getName(),
                positionsRequest.getLanguage(),
                positionsRequest.getLevel()
        );

        String hrId = extractUserId(request);

        String name = positionsRequest.getName();
        String level = positionsRequest.getLevel();
        if (name == null || name.isBlank() || level == null || level.isBlank()) {
            throw new CustomException(ErrorCode.MISSING_NAME_AND_LEVEL);
        }

        if (existing.isPresent()) {
            throw new CustomException(ErrorCode.DUPLICATE_POSITION);
        }

        // Upload file to Google Drive (Synchronous)
        DriveFileInfo driveFileInfo = storageService.uploadJD(
                positionsRequest.getFile(),
                positionsRequest.getName(),
                positionsRequest.getLanguage(),
                positionsRequest.getLevel()
        );

        String batchId = "JD_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + UUID.randomUUID().toString().substring(0, 4);
        processingBatchService.createBatch(batchId, null, 1, BatchType.JD_UPLOAD);

        // Save to DB initially as PENDING
        Positions position = new Positions();
        position.setHrId(hrId);
        position.setName(positionsRequest.getName());
        position.setLanguage(positionsRequest.getLanguage());
        position.setLevel(positionsRequest.getLevel());
        position.setJobDescription("Processing..."); // Placeholder
        position.setDriveFileId(driveFileInfo.getFileId());
        position.setDriveFileUrl(driveFileInfo.getWebViewLink());
        position.setBatchId(batchId);
        position.setStatus(JDStatus.PENDING);
        position.setCreatedAt(LocalDateTime.now());
        position.setUpdatedAt(LocalDateTime.now());

        Positions positionSaved = positionRepository.save(position);

        // Async Processing
        CompletableFuture.runAsync(() -> {
            String tempFilePath = null;
            try {
                // Update to PARSING
                Positions p = positionRepository.findById(positionSaved.getId());
                if (p != null) {
                    p.setStatus(JDStatus.PARSING);
                    p.setUpdatedAt(LocalDateTime.now());
                    positionRepository.save(p);
                }

                tempFilePath = storageService.downloadFileToTemp(driveFileInfo.getFileId());
                String jdText = llamaParseClient.parseJD(tempFilePath);

                p = positionRepository.findById(positionSaved.getId());
                if (p != null) {
                    p.setStatus(JDStatus.EMBEDDING);
                    p.setJobDescription(jdText);
                    p.setUpdatedAt(LocalDateTime.now());
                    positionRepository.save(p);

                    // Chunk & Publish
                    List<JDChunkPayload> chunks = jdChunkingService.chunk(
                            p.getId(), p.getName(),
                            p.getLanguage(), p.getLevel(), jdText
                    );
                    if (chunks.isEmpty()) {
                        log.warn("[Position] JD chunking produced no chunks for position {}, failing", p.getId());
                        p.setStatus(JDStatus.FAILED);
                        p.setErrorMessage("No chunks produced from JD text");
                        positionRepository.save(p);
                        processingBatchService.incrementProcessed(batchId, false);
                    } else {
                        publishJDChunkedEvent(p, chunks);
                    }
                }
            } catch (Exception e) {
                log.error("JD Parse/Chunk error details: " + e.getMessage(), e);
                Positions p = positionRepository.findById(positionSaved.getId());
                if (p != null) {
                    p.setStatus(JDStatus.FAILED);
                    p.setErrorMessage("Parsing failed: " + e.getMessage());
                    p.setUpdatedAt(LocalDateTime.now());
                    positionRepository.save(p);
                }
                processingBatchService.incrementProcessed(batchId, false);
            } finally {
                if (tempFilePath != null) {
                    storageService.deleteTempFile(tempFilePath);
                }
            }
        });

        PositionsResponse response = PositionsResponse.builder()
                .id(positionSaved.getId())
                .hrId(positionSaved.getHrId())
                .name(positionSaved.getName())
                .language(positionSaved.getLanguage())
                .level(positionSaved.getLevel())
                .driveFileUrl(positionSaved.getDriveFileUrl())
                .status(positionSaved.getStatus())
                .batchId(positionSaved.getBatchId())
                .createdAt(positionSaved.getCreatedAt())
                .updatedAt(positionSaved.getUpdatedAt())
                .build();

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                ErrorCode.SUCCESS.getMessage(),
                response
        );
    }

    public ApiResponse<List<PositionsResponse>> getPositions(String name, String language, String level) {
        List<Positions> positionsList = positionRepository.findByFilters(name, language, level);

        if(positionsList.isEmpty()) {
            throw new CustomException(ErrorCode.POSITION_NOT_FOUND);
        }

        List<PositionsResponse> response = positionsList.stream()
                .map(this::toResponse)
                .toList();

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                ErrorCode.SUCCESS.getMessage(),
                response
        );
    }

    public ApiResponse<PositionsResponse> getJdText(int positionId) {
        Positions position = positionRepository.findById(positionId);
        if(position == null) {
            throw new CustomException(ErrorCode.POSITION_NOT_FOUND);
        }

        PositionsResponse positionsResponse = PositionsResponse.builder()
                .name(position.getName())
                .jdText(position.getJobDescription())
                .build();

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                ErrorCode.SUCCESS.getMessage(),
                positionsResponse
        );
    }

    public ApiResponse<PageResponse<PositionsResponse>> getAllPositions(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Positions> positionPage = positionRepository.findAll(pageable);

        Page<PositionsResponse> mappedPage = positionPage.map(this::toResponse);

        return ApiResponse.<PageResponse<PositionsResponse>>builder()
                .statusCode(ErrorCode.SUCCESS.getCode())
                .message("Fetched all positions successfully")
                .data(PageUtil.toPageResponse(mappedPage))
                .timestamp(LocalDateTime.now())
                .build();
    }

    public ApiResponse<List<PositionsResponse>> searchPositions(String keyword) {
        if(keyword == null || keyword.trim().isEmpty()) {
            return new ApiResponse<>(ErrorCode.POSITION_NOT_FOUND.getCode(),
                    ErrorCode.POSITION_NOT_FOUND.getMessage());
        }

        String[] words = keyword.trim().toLowerCase().split("\\s+");
        List<Positions> all = positionRepository.findAll();

        List<Positions> filtered = all.stream()
                .filter(p -> {
                    String combined = (p.getName() + " " + p.getLanguage() + " " + p.getLevel())
                            .toLowerCase();
                    return Arrays.stream(words).allMatch(combined::contains);
                })
                .toList();

        List<PositionsResponse> responseList = filtered.stream()
                .map(this::toResponse)
                .toList();

        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), responseList);
    }

    @Transactional
    public void updatePosition(Integer positionId, PositionsRequest positionsRequest) {
        Positions position = positionRepository.findById(positionId)
                .orElseThrow(() -> new CustomException(ErrorCode.POSITION_NOT_FOUND));

        String name = positionsRequest.getName();
        String language = positionsRequest.getLanguage();
        String level = positionsRequest.getLevel();
        MultipartFile file = positionsRequest.getFile();

        String finalName = (name != null && !name.trim().isEmpty()) ? name.trim() : position.getName();
        String finalLang = (language != null && !language.trim().isEmpty()) ? language.trim() : position.getLanguage();
        String finalLevel = (level != null && !level.trim().isEmpty()) ? level.trim() : position.getLevel();

        // Check duplicate
        Optional<Positions> existing = positionRepository.findByNameAndLanguageAndLevel(finalName, finalLang, finalLevel);
        if (existing.isPresent() && existing.get().getId() != positionId) {
            throw new CustomException(ErrorCode.DUPLICATE_POSITION);
        }

        boolean isJDUpdated = (file != null && !file.isEmpty());
        String jdText = null;
        DriveFileInfo newFileInfo = null;

        if (isJDUpdated) {
            try {
                String url = embeddingServiceUrl + "/jd/" + positionId;
                restTemplate.delete(url);
                System.out.println("Deleted old embeddings for position: " + positionId);
            } catch (Exception e) {
                System.err.println("Failed to delete old embeddings for position " + positionId + ": " + e.getMessage());
            }

            newFileInfo = storageService.uploadJD(file, finalName, finalLang, finalLevel);

            String tempPath = null;

            try {
                // 2. Download về temp
                try {
                    tempPath = storageService.downloadFileToTemp(newFileInfo.getFileId());
                } catch (Exception e) {
                    storageService.deleteFile(newFileInfo.getFileId());
                    throw new CustomException(ErrorCode.FILE_DOWNLOAD_FAILED);
                }

                // 3. Parse JD
                try {
                    jdText = llamaParseClient.parseJD(tempPath);
                } catch (Exception e) {
                    storageService.deleteFile(newFileInfo.getFileId());
                    throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
                }

                if (jdText == null || jdText.trim().isEmpty()) {
                    storageService.deleteFile(newFileInfo.getFileId());
                    throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
                }

                // 4. Xóa file cũ
                try {
                    if (position.getDriveFileId() != null) {
                        storageService.deleteFile(position.getDriveFileId());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

            } finally {
                if (tempPath != null) {
                    storageService.deleteTempFile(tempPath);
                }
            }

            position.setDriveFileId(newFileInfo.getFileId());
            position.setDriveFileUrl(newFileInfo.getWebViewLink());
            position.setJobDescription(jdText);
        }

        else {
            boolean hasMetadataChange =
                    !finalName.equals(position.getName()) ||
                            !finalLang.equals(position.getLanguage()) ||
                            !finalLevel.equals(position.getLevel());

            if (hasMetadataChange && position.getDriveFileId() != null) {
                try {
                    DriveFileInfo movedInfo = storageService.moveJD(
                            position.getDriveFileId(),
                            finalName,
                            finalLang,
                            finalLevel
                    );
                    if (movedInfo != null) {
                        position.setDriveFileUrl(movedInfo.getWebViewLink());
                    }
                } catch (Exception e) {
                    throw new CustomException(ErrorCode.FILE_MOVE_FAILED);
                }
            }
        }

        position.setName(finalName);
        position.setLanguage(finalLang);
        position.setLevel(finalLevel);
        position.setUpdatedAt(LocalDateTime.now());

        positionRepository.save(position);

        if (jdText != null) {
            List<JDChunkPayload> chunks = jdChunkingService.chunk(
                    position.getId(), position.getName(),
                    position.getLanguage(), position.getLevel(), jdText
            );
            if (chunks.isEmpty()) {
                log.warn("[Position] JD chunking produced no chunks for position {}, skipping embed event",
                        position.getId());
            } else {
                Positions finalPosition = position;
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publishJDChunkedEvent(finalPosition, chunks);
                        }
                    });
                } else {
                    publishJDChunkedEvent(finalPosition, chunks);
                }
            }
        }
    }

    @Transactional
    public void deletePositions(List<Integer> positionIds) {

        for (Integer positionId : positionIds) {
            Positions position = positionRepository.findById(positionId)
                    .orElseThrow(() -> new CustomException(ErrorCode.POSITION_NOT_FOUND));

            boolean hasCV = !candidateCVRepository.findListCVsByPositionId(positionId).isEmpty();
            if (hasCV) {
                throw new CustomException(ErrorCode.CAN_NOT_DELETE_POSITION);
            }

            // Xóa embeddings trên Python service
            try {
                String url = embeddingServiceUrl + "/jd/" + positionId;
                restTemplate.delete(url);
                System.out.println("Deleted embeddings for position: " + positionId);
            } catch (Exception e) {
                System.err.println("Failed to delete embeddings for position " + positionId + ": " + e.getMessage());
                // Continue anyway - không block việc xóa position
            }

            // Xóa file trên Drive
            try {
                if (position.getDriveFileId() != null) {
                    storageService.deleteFile(position.getDriveFileId());
                }
            } catch (Exception e) {
                throw new CustomException(ErrorCode.FILE_DELETE_FAILED);
            }

            positionRepository.delete(position);
        }
    }

    // HELPER METHOD

    /**
     * Extract User ID từ request header
     */
    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACTION);
        }
        return userId;
    }

    private PositionsResponse toResponse(Positions position) {
        int totalCVs = candidateCVRepository.countByPositionId(position.getId());

        return PositionsResponse.builder()
                .id(position.getId())
                .hrId(position.getHrId())
                .name(position.getName())
                .language(position.getLanguage())
                .level(position.getLevel())
                .positionName(buildPositionName(
                        position.getName(),
                        position.getLanguage(),
                        position.getLevel()
                ))
                .jdPath(position.getJdPath())
                .driveFileUrl(position.getDriveFileUrl())
                .totalCVs(totalCVs)
                .isActive(position.isActive())
                .openedAt(position.getOpenedAt())
                .createdAt(position.getCreatedAt())
                .build();
    }

    /** Publishes a {@link JDChunkedEvent} to the JD chunked exchange after transaction commit. */
    private void publishJDChunkedEvent(Positions position, List<JDChunkPayload> chunks) {
        try {
            int totalTokens = chunks.stream().mapToInt(JDChunkPayload::getTokensEstimate).sum();
            String formattedName = (position.getLevel() != null ? position.getLevel() + " " : "")
                    + (position.getLanguage() != null ? position.getLanguage() + " " : "")
                    + (position.getName() != null ? position.getName() : "");
            formattedName = formattedName.trim();

            JDChunkedEvent event = new JDChunkedEvent(
                    position.getId(),
                    formattedName,
                    position.getLanguage(),
                    position.getLevel(),
                    chunks,
                    chunks.size(),
                    totalTokens,
                    position.getBatchId()
            );
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.JD_CHUNKED_EXCHANGE,
                    RabbitMQConfig.JD_CHUNKED_ROUTING_KEY,
                    event
            );
            log.info("[Position] Published JDChunkedEvent for position {} with {} chunks ({} tokens)",
                    position.getId(), chunks.size(), totalTokens);
        } catch (Exception e) {
            log.error("[Position] Failed to publish JDChunkedEvent for position {}: {}",
                    position.getId(), e.getMessage(), e);
        }
    }

    private String buildPositionName(String name, String language, String level) {
        return Stream.of(name, language, level)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
    }
}
