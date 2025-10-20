package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.CVUploadEvent;
import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.example.recruitmentservice.models.enums.BatchType;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UploadCVService {
    private final RabbitTemplate rabbitTemplate;
    private final CandidateCVRepository candidateCVRepository;
    private final StorageService storageService;
    private final PositionRepository positionRepository;
    private final ProcessingBatchService processingBatchService;

    public void uploadSingleCV(MultipartFile file, Integer positionId, String batchId) {
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
            cv.setBatchId(batchId);

            candidateCVRepository.save(cv);

            CVUploadEvent event = new CVUploadEvent(cv.getId(), filePath, positionId, batchId);

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CV_UPLOAD_QUEUE,
                    event
            );
            System.out.println("Event published to RabbitMQ - CV ID: " + cv.getId());

        } catch (CustomException e) {
            System.err.println("CustomException: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    public ApiResponse<Map<String, Object>> uploadMultipleCVs(
            List<MultipartFile> files,
            Integer positionId) {
        Positions position = positionRepository.findById(positionId)
                .orElseThrow(() -> new CustomException(ErrorCode.POSITION_NOT_FOUND));

        if (files == null || files.isEmpty()) {
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }

        String batchId = "POS" + positionId + "_"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "_B" + UUID.randomUUID().toString().substring(0, 4);

        ProcessingBatch batch = processingBatchService.createBatch(
                batchId,
                positionId,
                files.size(),
                BatchType.UPLOAD
        );

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            System.out.println("\n--- Processing file " + (i+1) + "/" + files.size() + " ---");

            try {
                if (file == null || file.isEmpty()) {
                System.err.println("File is null or empty!");
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
                }

                uploadSingleCV(file, positionId, batchId);
                System.out.println("File " + (i+1) + " uploaded successfully");

            } catch (CustomException e) {
                System.err.println("File " + (i + 1) + " failed: " + e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("batchId", batchId);
        response.put("message", "Please wait a moment. Your CVs are being processed.");
        response.put("totalCv", files.size());
        response.put("status", batch.getStatus());

        return new ApiResponse<>(200, "Batch created successfully", response);
    }
}
