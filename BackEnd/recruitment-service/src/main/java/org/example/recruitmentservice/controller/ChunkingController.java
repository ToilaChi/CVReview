package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.recruitmentservice.dto.request.ChunkPayload;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.services.chunking.ChunkingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/chunking")
@RequiredArgsConstructor
public class ChunkingController {

    private final CandidateCVRepository cvRepository;
    private final ChunkingService chunkingService;

    @PostMapping("")
    public ResponseEntity<?> chunking(@RequestBody Map<String, Integer> request) {
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
                cv.getCvContent()
        );

        // 4. Build response
        Map<String, Object> response = new HashMap<>();
        response.put("cvId", cv.getId());
        response.put("candidateId", cv.getCandidateId());
        response.put("candidateName", cv.getName());
        response.put("position", cv.getPosition() != null ? cv.getPosition().getName() : null);
        response.put("totalChunks", chunks.size());
        response.put("totalWords", chunks.stream().mapToInt(ChunkPayload::getWords).sum());
        response.put("totalTokens", chunks.stream().mapToInt(ChunkPayload::getTokensEstimate).sum());
        response.put("chunks", chunks);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{cvId}")
    public ResponseEntity<?> chunkingGet(@PathVariable Integer cvId) {
        return chunking(Map.of("cvId", cvId));
    }
}