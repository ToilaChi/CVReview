package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.recruitmentservice.dto.response.BatchStatusResponse;
import org.example.recruitmentservice.services.ProcessingBatchService;
import org.example.recruitmentservice.sse.SseEmitterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/tracking")
@RequiredArgsConstructor
public class ProcessingBatchController {

    private final ProcessingBatchService processingBatchService;
    private final SseEmitterRegistry sseEmitterRegistry;

    /** Fallback REST polling endpoint. FE uses this to fetch current status on reconnect. */
    @PreAuthorize("hasAnyRole('HR', 'CANDIDATE')")
    @GetMapping("/{batchId}/status")
    public ResponseEntity<ApiResponse<BatchStatusResponse>> getBatchStatus(@PathVariable String batchId) {
        return ResponseEntity.ok(processingBatchService.getBatchStatus(batchId));
    }

    /**
     * SSE streaming endpoint. FE opens this once and receives push events on every CV processed.
     * Timeout is 5 minutes — sufficient for the largest expected batches.
     *
     * Virtual Threads note: Spring MVC with virtual-threads enabled holds each SSE connection
     * on a virtual thread (not a platform thread), so hundreds of concurrent streams are safe.
     */
    @PreAuthorize("hasAnyRole('HR', 'CANDIDATE')")
    @GetMapping(value = "/{batchId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBatchStatus(@PathVariable String batchId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        sseEmitterRegistry.register(batchId, emitter);

        // Push current snapshot immediately so FE has data before the first CV finishes
        try {
            BatchStatusResponse current = processingBatchService.getBatchStatus(batchId).getData();
            emitter.send(SseEmitter.event().name("batch-update").data(current));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }
}

