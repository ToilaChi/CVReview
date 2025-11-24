package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.recruitmentservice.dto.request.ChunkPayload;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.services.ChunkingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class TestChunkingController {

    private final CandidateCVRepository cvRepository;
    private final ChunkingService chunkingService;

    /**
     * Test chunking với CV từ DB
     * POST /api/debug/test-chunking
     * Body: { "cvId": 123 }
     */
    @PostMapping("/test-chunking")
    public ResponseEntity<?> testChunking(@RequestBody Map<String, Integer> request) {
        Integer cvId = request.get("cvId");

        // 1. Lấy CV từ DB
        CandidateCV cv = cvRepository.findById(cvId)
                .orElseThrow(() -> new RuntimeException("CV not found: " + cvId));

        // 2. Check có content không
        if (cv.getCvContent() == null || cv.getCvContent().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "CV content is empty"));
        }

        // 3. Chunk
        List<ChunkPayload> chunks = chunkingService.chunk(
                cv,
                cv.getCvContent(),
                ChunkingService.TokenizerMode.ESTIMATE
        );

        // 4. Build response
        Map<String, Object> response = new HashMap<>();
        response.put("cvId", cv.getId());
        response.put("candidateId", cv.getCandidateId());
        response.put("candidateName", cv.getName());
        response.put("position", cv.getPosition() != null ? cv.getPosition().getName() : "Unknown");
        response.put("totalChunks", chunks.size());
        response.put("totalWords", chunks.stream().mapToInt(ChunkPayload::getWords).sum());
        response.put("totalTokens", chunks.stream().mapToInt(ChunkPayload::getTokensEstimate).sum());
        response.put("chunks", chunks);

        return ResponseEntity.ok(response);
    }

    /**
     * Test với cvId trực tiếp trong URL
     * GET /api/debug/test-chunking/123
     */
    @GetMapping("/test-chunking/{cvId}")
    public ResponseEntity<?> testChunkingGet(@PathVariable Integer cvId) {
        return testChunking(Map.of("cvId", cvId));
    }
}