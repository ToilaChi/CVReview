package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.recruitmentservice.services.AnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
