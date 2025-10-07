package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.ApiResponse;
import org.example.commonlibrary.dto.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.CVUploadEvent;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.models.CVStatus;
import org.example.recruitmentservice.models.CandidateCV;
import org.example.recruitmentservice.models.Positions;
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
    private final CandidateCVRepository cvRepository;
    private final StorageService storageService;
    private final PositionRepository positionRepository;

    public ApiResponse<CandidateCVResponse> uploadCV(MultipartFile file, Integer positionId) {
        System.out.println("\n========== START UPLOAD CV ==========");
        System.out.println("File: " + (file != null ? file.getOriginalFilename() : "NULL"));
        System.out.println("Size: " + (file != null ? file.getSize() + " bytes" : "NULL"));
        System.out.println("Position ID: " + positionId);

        try {
            if (file == null || file.isEmpty()) {
                System.err.println("File is null or empty!");
                throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
            }

            Positions position = positionRepository.findById(positionId)
                    .orElseThrow(() -> new CustomException(ErrorCode.POSITION_NOT_FOUND));
            System.out.println("Position found: " + position.getName());

            String jdPath = position.getJdPath();
            System.out.println("JD Path: " + jdPath);

            String baseDir = jdPath.substring(0, jdPath.lastIndexOf("/"));
            String cvDir = baseDir + "/CV";
            System.out.println("CV Dir: " + cvDir);

            String fileName = UUID.randomUUID() + "-Candidate_CV.pdf";
            String filePath = cvDir + "/" + fileName;
            System.out.println("Saving to: " + filePath);

            storageService.saveFile(file, filePath);
            System.out.println("File saved successfully!");

            System.out.println("Creating CandidateCV entity...");
            CandidateCV cv = new CandidateCV();
            cv.setPosition(position);
            cv.setCvPath(filePath);
            cv.setCvStatus(CVStatus.UPLOADED);
            cv.setUpdatedAt(LocalDateTime.now());

            System.out.println("CV Content: " + cv.getCvContent());

            System.out.println("Saving to database...");
            cvRepository.save(cv);
            System.out.println("Saved to DB - CV ID: " + cv.getId());

            System.out.println("Publishing event to RabbitMQ...");
            CVUploadEvent event = new CVUploadEvent(cv.getId(), filePath, positionId);
            System.out.println("Event: cvId=" + event.getCvId() + ", path=" + event.getFilePath());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CV_UPLOAD_QUEUE,
                    event
            );
            System.out.println("Event published to RabbitMQ - CV ID: " + cv.getId());

            CandidateCVResponse response = CandidateCVResponse.builder()
                    .cvId(cv.getId())
                    .positionId(positionId)
                    .fileName(fileName)
                    .filePath(filePath)
                    .status(CVStatus.UPLOADED)
                    .updatedAt(cv.getUpdatedAt())
                    .build();

            System.out.println("========== UPLOAD SUCCESS ==========\n");
            return new ApiResponse<>(200, "CV uploaded successfully", response);
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

        System.out.println("\n========== START BULK UPLOAD ==========");
        System.out.println("Number of files: " + files.size());
        System.out.println("Position ID: " + positionId);

        List<CandidateCVResponse> responses = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            System.out.println("\n--- Processing file " + (i+1) + "/" + files.size() + " ---");

            try {
                // Gọi lại method uploadCV đã có
                ApiResponse<CandidateCVResponse> result = uploadCV(file, positionId);
                responses.add(result.getData());
                System.out.println("File " + (i+1) + " uploaded successfully");

            } catch (Exception e) {
                System.err.println("File " + (i+1) + " failed: " + e.getMessage());
                errors.add(file.getOriginalFilename() + ": " + e.getMessage());
            }
        }

        System.out.println("\n========== BULK UPLOAD COMPLETED ==========");
        System.out.println("Success: " + responses.size());
        System.out.println("Failed: " + errors.size());

        String message = String.format(
                "Uploaded %d/%d CVs successfully",
                responses.size(),
                files.size()
        );

        if (!errors.isEmpty()) {
            message += ". Errors: " + String.join("; ", errors);
        }

        return new ApiResponse<>(200, message, responses);
    }
}
