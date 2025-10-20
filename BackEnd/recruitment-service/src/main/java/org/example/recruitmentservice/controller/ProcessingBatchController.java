package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.example.recruitmentservice.services.ProcessingBatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/tracking")
@RequiredArgsConstructor
public class ProcessingBatchController {

    private final ProcessingBatchService processingBatchService;

    @GetMapping("/{batchId}/status")
    public ApiResponse<Map<String, Object>> getBatchStatus(@PathVariable String batchId) {
        ProcessingBatch batch = processingBatchService.getBatch(batchId);

        Map<String, Object> data = new HashMap<>();
        data.put("batchId", batch.getBatchId());
        data.put("type", batch.getType());
        data.put("totalCv", batch.getTotalCv());
        data.put("processedCv", batch.getProcessedCv());
        data.put("status", batch.getStatus());
        data.put("createdAt", batch.getCreatedAt());
        data.put("completedAt", batch.getCompletedAt());

        return new ApiResponse<>(200, "Batch status fetched successfully", data);
    }
}

