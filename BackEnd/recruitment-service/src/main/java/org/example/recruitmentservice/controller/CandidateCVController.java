package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.ApiResponse;
import org.example.commonlibrary.dto.PageResponse;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.services.CandidateCVService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cv")
@RequiredArgsConstructor
public class CandidateCVController {
    private final CandidateCVService candidateCVService;

    @GetMapping("/position/{positionId}")
    public ApiResponse<PageResponse<CandidateCVResponse>> getAllCVsByPositionId(
            @PathVariable int positionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return candidateCVService.getAllCVsByPositionId(positionId, page, size);
    }
}
