package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.dto.response.PageResponse;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.services.CandidateCVService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/cv")
@RequiredArgsConstructor
public class CandidateCVController {
    private final CandidateCVService candidateCVService;

    @GetMapping("/{cvId}")
    public ApiResponse<CandidateCVResponse> getCVDetail(@PathVariable int cvId) {
        return candidateCVService.getCVDetail(cvId);
    }

    @GetMapping("/position/{positionId}")
    public ApiResponse<PageResponse<CandidateCVResponse>> getAllCVsByPositionId(
            @PathVariable int positionId,
            @RequestParam(required = false) List<CVStatus> statuses,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return candidateCVService.getAllCVsByPositionId(positionId, statuses, page, size);
    }

    @PostMapping("/{cvId}")
    public ResponseEntity<ApiResponse<Object>> updateCandidateCV(
            @PathVariable int cvId,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        candidateCVService.updateCV(cvId, file, name, email);
        return ResponseEntity.ok(new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "Updated Candidate CV successfully"));
    }

    @PostMapping("/{cvId}/{status}")
    public ResponseEntity<ApiResponse<Object>> updateCVStatus(
            @PathVariable int cvId,
            @PathVariable CVStatus status
            ) {
        candidateCVService.updateCVStatus(cvId, status);
        return ResponseEntity.ok(new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "Updated Candidate CV status successfully"));
    }

    @DeleteMapping("/{cvId}")
    public ResponseEntity<ApiResponse<Object>> deleteCandidateCV(
            @PathVariable int cvId) {
        candidateCVService.deleteCandidateCV(cvId);
        return ResponseEntity.ok(new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "Deleted Candidate CV successfully"));
    }
}
