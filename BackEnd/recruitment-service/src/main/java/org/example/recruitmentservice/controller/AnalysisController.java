package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.recruitmentservice.dto.request.ManualScoreRequest;
import org.example.recruitmentservice.dto.response.BatchRetryResponse;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.services.AnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisService analysisService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyzeCvs(
            @RequestParam int positionId,
            @RequestParam(required = false) List<Integer> cvIds) {
        return ResponseEntity.ok(analysisService.analyzeCvs(positionId, cvIds));
    }

    @PostMapping("/retry")
    public ResponseEntity<ApiResponse<BatchRetryResponse>> retry(@RequestParam String batchId) {
        return ResponseEntity.ok(analysisService.retryFailedCVsInBatch(batchId));
    }

    @PostMapping("/manual")
    public ResponseEntity<ApiResponse<CandidateCVResponse>> manualScore(
            @RequestParam int cvId,
            @RequestBody ManualScoreRequest manualScoreRequest) {
        return ResponseEntity.ok(analysisService.manualScore(cvId, manualScoreRequest));
    }
}
