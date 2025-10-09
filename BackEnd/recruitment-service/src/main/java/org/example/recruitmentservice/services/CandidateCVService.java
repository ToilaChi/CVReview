package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.ApiResponse;
import org.example.commonlibrary.dto.ErrorCode;
import org.example.commonlibrary.dto.PageResponse;
import org.example.commonlibrary.exception.CustomException;
import org.example.commonlibrary.utils.PageUtil;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.dto.response.PositionsResponse;
import org.example.recruitmentservice.models.CVStatus;
import org.example.recruitmentservice.models.CandidateCV;
import org.example.recruitmentservice.models.Positions;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CandidateCVService {
    private final CandidateCVRepository candidateCVRepository;
    private final PositionRepository positionRepository;

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
}
