package org.example.recruitmentservice.services;

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

@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository positionRepository;
    private final LlamaParseClient llamaParseClient;
    private final StorageService storageService;
    private final CandidateCVRepository candidateCVRepository;

    @Transactional
    public ApiResponse<PositionsResponse> createPosition(PositionsRequest positionsRequest) {
        //Check duplicate
        Optional<Positions> existing = positionRepository.findByNameAndLanguageAndLevel(
                positionsRequest.getName(),
                positionsRequest.getLanguage(),
                positionsRequest.getLevel()
        );

        if (existing.isPresent()) {
            throw new CustomException(ErrorCode.DUPLICATE_POSITION);
        }

        // Upload file
        String jdPath = storageService.uploadJD(
                positionsRequest.getFile(),
                positionsRequest.getName(),
                positionsRequest.getLanguage(),
                positionsRequest.getLevel()
        );

        // Parse file by LlamaParse
        String jdText;
        try {
            String absolutePath = storageService.getAbsolutePath(jdPath);
            jdText = llamaParseClient.parseJD(absolutePath);
        } catch (Exception e) {
            System.err.println("Parse error details: " + e.getMessage());
            e.printStackTrace(); // In full stack trace
            throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
        }

        // Save db
        Positions position = new Positions();
        position.setName(positionsRequest.getName());
        position.setLanguage(positionsRequest.getLanguage());
        position.setLevel(positionsRequest.getLevel());
        position.setJobDescription(jdText);
        position.setJdPath(jdPath);
        position.setCreatedAt(LocalDateTime.now());
        position.setUpdatedAt(LocalDateTime.now());

        Positions positionSaved = positionRepository.save(position);

        PositionsResponse response = new PositionsResponse(
                positionSaved.getId(),
                positionSaved.getName(),
                positionSaved.getLanguage(),
                positionSaved.getLevel(),
                positionSaved.getJdPath(),
                positionSaved.getCreatedAt(),
                positionSaved.getUpdatedAt()
        );

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
                .map(PositionsResponse::new)
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

        Page<PositionsResponse> mappedPage = positionPage.map(position ->
                PositionsResponse.builder()
                        .id(position.getId())
                        .name(position.getName())
                        .language(position.getLanguage())
                        .level(position.getLevel())
                        .jdPath(position.getJdPath())
                        .createdAt(position.getCreatedAt())
                        .build()
        );

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

        // Filter
        List<Positions> filtered = all.stream()
                .filter(p -> {
                    String combined = (p.getName() + " " + p.getLanguage() + " " + p.getLevel())
                            .toLowerCase();
                    // Bắt buộc tất cả từ phải match (AND)
                    return Arrays.stream(words).allMatch(combined::contains);
                })
                .toList();

        List<PositionsResponse> responseList = filtered.stream()
                .map(p -> new PositionsResponse(
                        p.getId(),
                        p.getName(),
                        p.getLanguage(),
                        p.getLevel(),
                        p.getJdPath()
                ))
                .toList();

        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), responseList);
    }

    @Transactional
    public void updatePosition(int positionId, String name, String language, String level, MultipartFile file) {
        Positions position = positionRepository.findById(positionId);
        if (position == null) {
            throw new CustomException(ErrorCode.POSITION_NOT_FOUND);
        }

        String finalName = (name != null && !name.trim().isEmpty()) ? name.trim() : null;
        String finalLang = (language != null && !language.trim().isEmpty()) ? language.trim() : null;
        String finalLevel = (level != null && !level.trim().isEmpty()) ? level.trim() : null;

        // Check duplicate
        if (name != null && language != null && level != null) {
            Optional<Positions> existing = positionRepository.findByNameAndLanguageAndLevel(finalName, finalLang, finalLevel);
            if (existing.isPresent() && existing.get().getId() != positionId) {
                throw new CustomException(ErrorCode.DUPLICATE_POSITION);
            }
        }

        boolean isJDUpdated = (file != null && !file.isEmpty());

        if (isJDUpdated) {
            String newFilePath = storageService.uploadJD(file, finalName, finalLang, finalLevel);

            try {
                String absolutePath = storageService.getAbsolutePath(newFilePath);
                String jdText = llamaParseClient.parseJD(absolutePath);

                if (jdText == null || jdText.isEmpty()) {
                    storageService.deleteFile(newFilePath);
                    throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
                }

                // Xóa file cũ
                String oldFilePath = position.getJdPath();
                if (oldFilePath != null) {
                    storageService.deleteFile(oldFilePath);
                }

                position.setJdPath(newFilePath);
                position.setJobDescription(jdText);
            } catch (Exception e) {
                storageService.deleteFile(newFilePath);
                throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
            }
        } else {
            boolean hasAtLeastOneMeta =
                    (finalName != null && !finalName.isBlank()) ||
                    (finalLang != null && !finalLang.isBlank()) ||
                    (finalLevel != null && !finalLevel.isBlank());

            if (hasAtLeastOneMeta && position.getJdPath() != null) {
                try {
                    String movedPath = storageService.moveJD(position.getJdPath(), finalName, finalLang, finalLevel);
                    System.out.println("movedPath: " + movedPath);
                    position.setJdPath(movedPath);
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new CustomException(ErrorCode.FILE_MOVE_FAILED);
                }
            }

        }

        System.out.println("Path: " + position.getJdPath());
        position.setName(finalName);
        position.setLanguage(finalLang);
        position.setLevel(finalLevel);
        position.setUpdatedAt(LocalDateTime.now());

        positionRepository.save(position);
    }

    @Transactional
    public void deletePosition(int positionId) {
        Positions position = positionRepository.findById(positionId);
        if(position == null) {
            throw new CustomException(ErrorCode.POSITION_NOT_FOUND);
        }

        List<CandidateCV> candidateCVS = candidateCVRepository.findListCVsByPositionId(positionId);
        if (candidateCVS != null) {
            throw new CustomException(ErrorCode.CAN_NOT_DELETE_POSITION);
        }

        try {
            storageService.deleteFile(position.getJdPath());
        } catch (Exception e) {
            throw new CustomException(ErrorCode.FILE_DELETE_FAILED);
        }

        positionRepository.delete(position);
    }
}
