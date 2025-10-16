package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.CVUploadEvent;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadCVService {
    private final RabbitTemplate rabbitTemplate;
    private final CandidateCVRepository candidateCVRepository;
    private final StorageService storageService;
    private final PositionRepository positionRepository;

    public CandidateCVResponse uploadSingleCV(MultipartFile file, Integer positionId) {
        try {
            if (file == null || file.isEmpty()) {
                System.err.println("File is null or empty!");
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
            }

            Positions position = positionRepository.findById(positionId)
                    .orElseThrow(() -> new CustomException(ErrorCode.POSITION_NOT_FOUND));

            String jdPath = position.getJdPath();

            String baseDir = jdPath.substring(0, jdPath.lastIndexOf("/"));
            String cvDir = baseDir + "/CV";

            String fileName = UUID.randomUUID() + "-" +
                    file.getOriginalFilename();
            String filePath = cvDir + "/" + fileName;

            storageService.saveFile(file, filePath);

            CandidateCV cv = new CandidateCV();
            cv.setPosition(position);
            cv.setCvPath(filePath);
            cv.setCvStatus(CVStatus.UPLOADED);
            cv.setUpdatedAt(LocalDateTime.now());

            candidateCVRepository.save(cv);

            CVUploadEvent event = new CVUploadEvent(cv.getId(), filePath, positionId);

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CV_UPLOAD_QUEUE,
                    event
            );
            System.out.println("Event published to RabbitMQ - CV ID: " + cv.getId());

            return CandidateCVResponse.builder()
                    .cvId(cv.getId())
                    .positionId(positionId)
                    .fileName(fileName)
                    .filePath(filePath)
                    .status(CVStatus.UPLOADED)
                    .updatedAt(cv.getUpdatedAt())
                    .build();

        } catch (CustomException e) {
            System.err.println("CustomException: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    public ApiResponse<List<CandidateCVResponse>> uploadMultipleCVs(
            List<MultipartFile> files,
            Integer positionId) {
        Positions position = positionRepository.findById(positionId)
                .orElseThrow(() -> new CustomException(ErrorCode.POSITION_NOT_FOUND));

        List<CandidateCVResponse> responses = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            System.out.println("\n--- Processing file " + (i+1) + "/" + files.size() + " ---");

            try {
                if (file == null || file.isEmpty()) {
                System.err.println("File is null or empty!");
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
                }

                // Call back existing CV upload method
                CandidateCVResponse result = uploadSingleCV(file, positionId);
                responses.add(result);
                System.out.println("File " + (i+1) + " uploaded successfully");

            } catch (Exception e) {
                System.err.println("File " + (i+1) + " failed: " + e.getMessage());
                throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
            }
        }

        String message = String.format(
                "Uploaded %d/%d CVs successfully",
                responses.size(),
                files.size()
        );

        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), message, responses);
    }
}
