package org.example.recruitmentservice.services;

import org.example.recruitmentservice.dto.response.DriveFileInfo;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.dto.response.PageResponse;
import org.example.commonlibrary.exception.CustomException;
import org.example.commonlibrary.utils.PageUtil;
import org.example.recruitmentservice.client.LlamaParseClient;
import org.example.recruitmentservice.dto.request.PositionsRequest;
import org.example.recruitmentservice.dto.response.PositionsResponse;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository positionRepository;
    private final LlamaParseClient llamaParseClient;
    private final StorageService storageService;
    private final CandidateCVRepository candidateCVRepository;

    @Transactional
    public ApiResponse<PositionsResponse> createPosition(PositionsRequest positionsRequest) {
        // Check duplicate
        Optional<Positions> existing = positionRepository.findByNameAndLanguageAndLevel(
                positionsRequest.getName(),
                positionsRequest.getLanguage(),
                positionsRequest.getLevel()
        );

        String name = positionsRequest.getName();
        String level = positionsRequest.getLevel();
        if (name == null || name.isBlank() || level == null || level.isBlank()) {
            throw new CustomException(ErrorCode.MISSING_NAME_AND_LEVEL);
        }

        if (existing.isPresent()) {
            throw new CustomException(ErrorCode.DUPLICATE_POSITION);
        }

        // Upload file to Google Drive
        DriveFileInfo driveFileInfo = storageService.uploadJD(
                positionsRequest.getFile(),
                positionsRequest.getName(),
                positionsRequest.getLanguage(),
                positionsRequest.getLevel()
        );

        // Parse file by LlamaParse
        String jdText;
        String tempFilePath = null;
        try {
            // Download từ Drive về temp
            tempFilePath = storageService.downloadFileToTemp(driveFileInfo.getFileId());
            jdText = llamaParseClient.parseJD(tempFilePath);
        } catch (Exception e) {
            // Nếu parse fail, xóa file trên Drive
            storageService.deleteFile(driveFileInfo.getFileId());
            System.err.println("Parse error details: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
        } finally {
            // Cleanup temp file
            if (tempFilePath != null) {
                storageService.deleteTempFile(tempFilePath);
            }
        }

        // Save to DB
        Positions position = new Positions();
        position.setName(positionsRequest.getName());
        position.setLanguage(positionsRequest.getLanguage());
        position.setLevel(positionsRequest.getLevel());
        position.setJobDescription(jdText);

        // Lưu Drive info
        position.setDriveFileId(driveFileInfo.getFileId());
        position.setDriveFileUrl(driveFileInfo.getWebViewLink());
        // jdPath để null (deprecated)

        position.setCreatedAt(LocalDateTime.now());
        position.setUpdatedAt(LocalDateTime.now());

        Positions positionSaved = positionRepository.save(position);

        PositionsResponse response = PositionsResponse.builder()
                .id(positionSaved.getId())
                .name(positionSaved.getName())
                .language(positionSaved.getLanguage())
                .level(positionSaved.getLevel())
                .driveFileUrl(positionSaved.getDriveFileUrl())
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

        if (isJDUpdated) {
            // Upload file mới lên Drive
            DriveFileInfo newFileInfo = storageService.uploadJD(file, finalName, finalLang, finalLevel);

            String tempFilePath = null;
            try {
                // Download về temp để parse
                tempFilePath = storageService.downloadFileToTemp(newFileInfo.getFileId());
                String jdText = llamaParseClient.parseJD(tempFilePath);

                if (jdText == null || jdText.isEmpty()) {
                    storageService.deleteFile(newFileInfo.getFileId());
                    throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
                }

                // Xóa file cũ trên Drive
                String oldFileId = position.getDriveFileId();
                if (oldFileId != null) {
                    storageService.deleteFile(oldFileId);
                }

                // Update position
                position.setDriveFileId(newFileInfo.getFileId());
                position.setDriveFileUrl(newFileInfo.getWebViewLink());
                position.setJobDescription(jdText);

            } catch (Exception e) {
                storageService.deleteFile(newFileInfo.getFileId());
                throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
            } finally {
                if (tempFilePath != null) {
                    storageService.deleteTempFile(tempFilePath);
                }
            }

        } else {
            // Chỉ update metadata → move file trên Drive
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
                    e.printStackTrace();
                    throw new CustomException(ErrorCode.FILE_MOVE_FAILED);
                }
            }
        }

        position.setName(finalName);
        position.setLanguage(finalLang);
        position.setLevel(finalLevel);
        position.setUpdatedAt(LocalDateTime.now());

        positionRepository.save(position);
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

    private PositionsResponse toResponse(Positions position) {
        int totalCVs = candidateCVRepository.countByPositionId(position.getId());

        return PositionsResponse.builder()
                .id(position.getId())
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
                .createdAt(position.getCreatedAt())
                .build();
    }

    private String buildPositionName(String name, String language, String level) {
        return Stream.of(name, language, level)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
    }
}
