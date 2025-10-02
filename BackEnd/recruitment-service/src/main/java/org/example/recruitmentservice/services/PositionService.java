package org.example.recruitmentservice.services;

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
import java.util.Optional;

@Service
public class PositionService {
    private final PositionRepository positionRepository;
    private final LlamaParseClient llamaParseClient;
    private final StorageService storageService;

    public PositionService(PositionRepository positionRepository, LlamaParseClient llamaParseClient, StorageService storageService) {
        this.positionRepository = positionRepository;
        this.llamaParseClient = llamaParseClient;
        this.storageService = storageService;
    }

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

        // Upload file CloudFlare
        String jdPath = storageService.uploadFile(positionsRequest.getFile(), "jd");

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
}
