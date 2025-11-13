package org.example.recruitmentservice.services;

import jakarta.servlet.http.HttpServletRequest;
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
import org.example.recruitmentservice.models.enums.SourceType;
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

    /**
     * Upload CV cho HR (multiple CVs cho một position cụ thể)
     */
    public ApiResponse<Map<String, Object>> uploadCVsByHR(
            List<MultipartFile> files,
            Integer positionId,
            HttpServletRequest request) {

        String role = extractAndValidateRole(request);
        if (!"HR".equalsIgnoreCase(role)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACTION);
        }

        Positions position = positionRepository.findById(positionId)
                .orElseThrow(() -> new CustomException(ErrorCode.POSITION_NOT_FOUND));

        if (files == null || files.isEmpty()) {
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }

        String batchId = generateHRBatchId(positionId);

        ProcessingBatch batch = processingBatchService.createBatch(
                batchId,
                positionId,
                files.size(),
                BatchType.UPLOAD
        );

        int successCount = 0;
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            System.out.println("\n--- Processing file " + (i + 1) + "/" + files.size() + " ---");

            try {
                uploadSingleCV(file, position, batchId, SourceType.HR);
                successCount++;
                System.out.println("File " + (i + 1) + " uploaded successfully");
            } catch (Exception e) {
                System.err.println("File " + (i + 1) + " failed: " + e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("batchId", batchId);
        response.put("message", "Please wait a moment. Your CVs are being processed.");
        response.put("totalCv", files.size());
        response.put("successCount", successCount);
        response.put("status", batch.getStatus());

        return new ApiResponse<>(200, "Batch created successfully", response);
    }

    /**
     * Upload CV cho CANDIDATE (single CV, không cần positionId)
     */
    public ApiResponse<Map<String, Object>> uploadCVByCandidate(
            MultipartFile file,
            HttpServletRequest request) {

        String role = extractAndValidateRole(request);
        if (!"CANDIDATE".equalsIgnoreCase(role)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACTION);
        }

        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }

        String batchId = generateCandidateBatchId();

        ProcessingBatch batch = processingBatchService.createBatch(
                batchId,
                null, // Không có positionId
                1,
                BatchType.UPLOAD
        );

        try {
            CandidateCV cv = uploadSingleCV(file, null, batchId, SourceType.CANDIDATE);

            Map<String, Object> response = new HashMap<>();
            response.put("cvId", cv.getId());
            response.put("batchId", batchId);
            response.put("message", "Your CV has been uploaded successfully and is being processed.");
            response.put("status", batch.getStatus());

            return new ApiResponse<>(200, "CV uploaded successfully", response);
        } catch (Exception e) {
            System.err.println("Candidate CV upload failed: " + e.getMessage());
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    /**
     * Core method để upload một CV
     * @param position - có thể null nếu là CANDIDATE upload
     */
    private CandidateCV uploadSingleCV(
            MultipartFile file,
            Positions position,
            String batchId,
            SourceType sourceType) {

        try {
            if (file == null || file.isEmpty()) {
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
            }

            String filePath = buildFilePath(file, position);
            storageService.saveFile(file, filePath);

            CandidateCV cv = new CandidateCV();
            cv.setPosition(position); // Có thể null cho CANDIDATE
            cv.setCvPath(filePath);
            cv.setCvStatus(CVStatus.UPLOADED);
            cv.setUpdatedAt(LocalDateTime.now());
            cv.setBatchId(batchId);
            cv.setSourceType(sourceType);

            candidateCVRepository.save(cv);

            // Publish event to RabbitMQ
            CVUploadEvent event = new CVUploadEvent(
                    cv.getId(),
                    filePath,
                    position != null ? position.getId() : null,
                    batchId
            );

            rabbitTemplate.convertAndSend(RabbitMQConfig.CV_UPLOAD_QUEUE, event);
            System.out.println("Event published to RabbitMQ - CV ID: " + cv.getId());

            return cv;

        } catch (CustomException e) {
            System.err.println("CustomException: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    /**
     * Build file path base on position hoặc dùng thư mục chung cho CANDIDATE
     */
    private String buildFilePath(MultipartFile file, Positions position) {
        String cvDir;

        if (position != null && position.getJdPath() != null) {
            // HR upload - lưu theo position
            String jdPath = position.getJdPath();
            String baseDir = jdPath.substring(0, jdPath.lastIndexOf("/"));
            cvDir = baseDir + "/CV";
        } else {
            // CANDIDATE upload - lưu vào thư mục chung
            cvDir = "candidate-cvs/" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        }

        String fileName = UUID.randomUUID() + "-" + file.getOriginalFilename();
        return cvDir + "/" + fileName;
    }

    /**
     * Generate batch ID cho HR (có positionId)
     */
    private String generateHRBatchId(Integer positionId) {
        return "POS" + positionId + "_"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "_B" + UUID.randomUUID().toString().substring(0, 4);
    }

    /**
     * Generate batch ID cho CANDIDATE (không có positionId)
     */
    private String generateCandidateBatchId() {
        return "CAND_"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Extract và validate role từ request
     */
    private String extractAndValidateRole(HttpServletRequest request) {
        String role = request.getHeader("X-User-Role");
        if (role == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACTION);
        }
        return role;
    }
}
