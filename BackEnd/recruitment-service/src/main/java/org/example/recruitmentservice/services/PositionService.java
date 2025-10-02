package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.ApiResponse;
import org.example.commonlibrary.dto.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.client.LlamaParseClient;
import org.example.recruitmentservice.dto.request.PositionsRequest;
import org.example.recruitmentservice.dto.response.PositionsResponse;
import org.example.recruitmentservice.models.Positions;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.stereotype.Service;

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
        String jdPath = storageService.uploadFile(
                positionsRequest.getFile(),
                positionsRequest.getName(),
                positionsRequest.getLanguage(),
                positionsRequest.getLevel()
        );

        // Parse file by LlamaParse
        String jdText;
        try {
            jdText = llamaParseClient.parseFile(jdPath);
        } catch (Exception e) {
            System.err.println("Parse error details: " + e.getMessage());
            e.printStackTrace(); // In full stack trace
            throw new CustomException(ErrorCode.CV_PARSE_FAILED);
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
                .map(PositionsResponse::new) // dùng constructor DTO
                .toList();

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                ErrorCode.SUCCESS.getMessage(),
                response
        );
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
}
