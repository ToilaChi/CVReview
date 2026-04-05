package org.example.recruitmentservice.sse;

import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.dto.response.BatchStatusResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds active SSE emitters keyed by batchId.
 *
 * Thread-safety: ConcurrentHashMap ensures safe concurrent access from
 * multiple Virtual Threads (one per incoming RabbitMQ message).
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Registers a new SSE emitter for the given batch and wires up cleanup callbacks.
     * Cleanup on timeout/error/completion prevents memory leaks from stale emitters.
     */
    public void register(String batchId, SseEmitter emitter) {
        emitters.put(batchId, emitter);
        log.info("SSE emitter registered for batch: {}", batchId);

        Runnable cleanup = () -> {
            emitters.remove(batchId);
            log.debug("SSE emitter removed for batch: {}", batchId);
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.warn("SSE emitter error for batch {}: {}", batchId, e.getMessage());
            cleanup.run();
        });
    }

    /**
     * Pushes a batch status snapshot to the FE client listening on this batch.
     * Silently removes the emitter if the connection is already closed.
     */
    public void send(String batchId, BatchStatusResponse data) {
        SseEmitter emitter = emitters.get(batchId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .name("batch-update")
                    .data(data));
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send SSE event for batch {}, removing emitter. Cause: {}",
                    batchId, e.getMessage());
            emitters.remove(batchId);
        }
    }

    /**
     * Sends the final event and closes the SSE stream for the given batch.
     * Called when batch transitions to COMPLETED or a fatal error state.
     */
    public void complete(String batchId) {
        SseEmitter emitter = emitters.remove(batchId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .name("batch-completed")
                    .data("DONE"));
            emitter.complete();
            log.info("SSE stream completed for batch: {}", batchId);
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to complete SSE emitter for batch {}: {}", batchId, e.getMessage());
        }
    }
}
