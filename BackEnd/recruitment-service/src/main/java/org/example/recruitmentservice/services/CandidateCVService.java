package org.example.recruitmentservice.services;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.dto.response.PageResponse;
import org.example.commonlibrary.exception.CustomException;
import org.example.commonlibrary.utils.PageUtil;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.CVUploadEvent;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.example.recruitmentservice.repository.ProcessingBatchRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateCVService {
    private final CandidateCVRepository candidateCVRepository;
    private final PositionRepository positionRepository;
    private final StorageService storageService;
    private final RabbitTemplate rabbitTemplate;
    private final ProcessingBatchRepository processingBatchRepository;

    public ApiResponse<CandidateCVResponse> getCVDetail(int cvId) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        CandidateCVResponse response = CandidateCVResponse.builder()
                .cvId(cv.getId())
                .positionId(cv.getPosition().getId())
                .name(cv.getName())
                .email(cv.getEmail())
                .status(cv.getCvStatus())
                .errorMessage(cv.getErrorMessage())
                .failedAt(cv.getFailedAt())
                .retryCount(cv.getRetryCount())
                .canRetry(cv.getCvStatus() == CVStatus.FAILED)
                .updatedAt(cv.getUpdatedAt())
                .parsedAt(cv.getParsedAt())
                .scoredAt(cv.getScoredAt())
                .build();

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "CV detail retrieved successfully",
                response
        );
    }

    public ApiResponse<PageResponse<CandidateCVResponse>> getAllCVsByPositionId(int positionId, int page, int size) {
        Positions position = positionRepository.findById(positionId);
        if(position == null) {
            throw new CustomException(ErrorCode.POSITION_NOT_FOUND);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());
        Page<CandidateCV> cvPage = candidateCVRepository.findByPositionId(positionId, pageable);

        Page<CandidateCVResponse> mappedPage = cvPage.map(cv ->
                CandidateCVResponse.builder()
                        .cvId(cv.getId())
                        .positionId(cv.getPosition().getId())
                        .status(CVStatus.valueOf(cv.getCvStatus().name()))
                        .name(cv.getName())
                        .email(cv.getEmail())
                        .updatedAt(cv.getUpdatedAt())
                        .filePath(cv.getCvPath())
                        .build()
        );

        return ApiResponse.<PageResponse<CandidateCVResponse>>builder()
                .statusCode(ErrorCode.SUCCESS.getCode())
                .message("Fetched all CVs for position: " + position.getName() + " " + position.getLanguage() + " " + position.getLevel())
                .data(PageUtil.toPageResponse(mappedPage))
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Transactional
    public void updateCV(int cvId, MultipartFile newFile, String name, String email) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        // If just update name and mail
        if (newFile == null || newFile.isEmpty()) {
            if (name != null && !name.trim().isEmpty()) {
                cv.setName(name.trim());
            }
            if (email != null && !email.trim().isEmpty()) {
                cv.setEmail(email.trim());
            }

            cv.setUpdatedAt(LocalDateTime.now());
            candidateCVRepository.save(cv);
            return;
        }

        // Upload new file
        try {
            String oldFilePath = cv.getCvPath();
            if (oldFilePath != null && !oldFilePath.isEmpty()) {
                storageService.deleteFile(oldFilePath);
            }

            Positions position = cv.getPosition();
            if (position == null) {
                throw new CustomException(ErrorCode.POSITION_NOT_FOUND);
            }

            String jdPath = position.getJdPath();
            String baseDir = jdPath.substring(0, jdPath.lastIndexOf("/"));
            String cvDir = baseDir + "/CV";

            String newFileName = UUID.randomUUID() + "-" + newFile.getOriginalFilename();
            String newFilePath = cvDir + "/" + newFileName;

            storageService.saveFile(newFile, newFilePath);

            cv.setCvPath(newFilePath);
            cv.setCvStatus(CVStatus.UPLOADED);
            cv.setName(null);
            cv.setEmail(null);
            cv.setCvContent(null);
            cv.setUpdatedAt(LocalDateTime.now());
            candidateCVRepository.save(cv);

            CVUploadEvent event = new CVUploadEvent(cv.getId(), newFilePath, position.getId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.CV_UPLOAD_QUEUE, event);
        } catch (CustomException e) {
            System.err.println("CustomException while updating CV: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.err.println("Unexpected error while updating CV: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

//    @Transactional
//    public ApiResponse<CandidateCVResponse> retryCV(String batchId) {
//        log.info("[RETRY-BATCH] Starting retry for failed CVs in batch: {}", batchId);
//
//        ProcessingBatch batch = processingBatchRepository.findByBatchId(batchId)
//                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));
//
//        List<CandidateCV> failedCVs = candidateCVRepository
//                .findByBatchIdAndCvStatus(batchId, CVStatus.FAILED);
//
//        if (failedCVs.isEmpty()) {
//            log.warn("[RETRY-BATCH] No failed CVs found in batch: {}", batchId);
//            throw new CustomException(ErrorCode.NO_FAILED_CVS_IN_BATCH);
//        }
//
//        log.info("[RETRY-BATCH] Found {} failed CVs to retry", failedCVs.size());
//    }

    @Transactional
    public void deleteCandidateCV(int cvId) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        try {
            storageService.deleteFile(cv.getCvPath());
        } catch (Exception e) {
            throw new CustomException(ErrorCode.FILE_DELETE_FAILED);
        }

        candidateCVRepository.delete(cv);
    }
}
