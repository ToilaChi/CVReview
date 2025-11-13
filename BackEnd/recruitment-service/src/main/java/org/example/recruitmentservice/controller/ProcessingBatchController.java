package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.recruitmentservice.dto.response.BatchStatusResponse;
import org.example.recruitmentservice.services.ProcessingBatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tracking")
@RequiredArgsConstructor
public class ProcessingBatchController {

    private final ProcessingBatchService processingBatchService;

    @PreAuthorize("hasAnyRole('HR', 'CANDIDATE')")
    @GetMapping("/{batchId}/status")
    public ResponseEntity<ApiResponse<BatchStatusResponse>> getBatchStatus(@PathVariable String batchId) {
        return ResponseEntity.ok(processingBatchService.getBatchStatus(batchId));
    }
}

