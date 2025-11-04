package org.example.recruitmentservice.services;

import org.springframework.transaction.annotation.Transactional;
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
import org.example.recruitmentservice.models.entity.CVAnalysis;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CVAnalysisRepository;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateCVService {
    private final CandidateCVRepository candidateCVRepository;
    private final PositionRepository positionRepository;
    private final CVAnalysisRepository cvAnalysisRepository;
    private final StorageService storageService;
    private final RabbitTemplate rabbitTemplate;

    public ApiResponse<CandidateCVResponse> getCVDetail(int cvId) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        Optional<CVAnalysis> cvAnalysisOpt = cvAnalysisRepository.findByCandidateCV_Id(cvId);
        CVAnalysis cvAnalysis = cvAnalysisOpt.orElse(null);

        CandidateCVResponse response = CandidateCVResponse.builder()
                .cvId(cv.getId())
                .positionId(cv.getPosition().getId())
                .name(cv.getName())
                .email(cv.getEmail())
                .batchId(cv.getBatchId())
                .filePath(cv.getCvPath())
                .score(cvAnalysis != null ? cvAnalysis.getScore() : null)
                .feedback(cvAnalysis != null ? cvAnalysis.getFeedback() : null)
                .skillMatch(cvAnalysis != null ? cvAnalysis.getSkillMatch() : null)
                .skillMiss(cvAnalysis != null ? cvAnalysis.getSkillMiss() : null)
                .status(cv.getCvStatus())
                .errorMessage(cv.getErrorMessage())
                .failedAt(cv.getFailedAt())
                .retryCount(cv.getRetryCount())
                .canRetry(cv.getCvStatus() == CVStatus.FAILED)
                .analyzedAt(cvAnalysis != null ? cvAnalysis.getAnalyzedAt() : null)
                .build();

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "CV detail retrieved successfully",
                response
        );
    }

    public ApiResponse<PageResponse<CandidateCVResponse>> getAllCVsByPositionId(int positionId, List<CVStatus> statuses, int page, int size) {
        Positions position = positionRepository.findById(positionId);
        if(position == null) {
            throw new CustomException(ErrorCode.POSITION_NOT_FOUND);
        }

        if (statuses == null || statuses.isEmpty()) {
            statuses = List.of(CVStatus.PARSED, CVStatus.SCORED, CVStatus.FAILED);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());
        Page<CandidateCV> cvPage = candidateCVRepository.findByPositionIdAndCvStatusIn(positionId, statuses, pageable);

        Page<CandidateCVResponse> mappedPage = cvPage.map(cv ->
                CandidateCVResponse.builder()
                        .cvId(cv.getId())
                        .positionId(cv.getPosition().getId())
                        .batchId(cv.getBatchId())
                        .status(cv.getCvStatus())
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

            CVUploadEvent event = new CVUploadEvent(cv.getId(), newFilePath, position.getId(), cv.getBatchId());
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

    @Transactional
    public void updateCVStatus(int cvId, CVStatus status) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));
        cv.setCvStatus(status);
        cv.setUpdatedAt(LocalDateTime.now());
        candidateCVRepository.save(cv);
    }

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
