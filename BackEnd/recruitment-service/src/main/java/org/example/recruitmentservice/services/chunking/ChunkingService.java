package org.example.recruitmentservice.services.chunking;

import org.example.recruitmentservice.dto.request.ChunkPayload;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.services.chunking.config.ChunkingConfig;
import org.example.recruitmentservice.services.chunking.strategy.ChunkingStrategy;
import org.example.recruitmentservice.services.metadata.MetadataExtractor;
import org.example.recruitmentservice.services.metadata.model.CVMetadata;
import org.example.recruitmentservice.services.summary.SummaryGenerator;
import org.example.recruitmentservice.services.text.TextNormalizer;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final ChunkingConfig config;
    private final TextNormalizer textNormalizer;
    private final MetadataExtractor metadataExtractor;
    private final SummaryGenerator summaryGenerator;
    private final ChunkingStrategy chunkingStrategy;

    /**
     * Main entry point - simplified orchestration
     */
    public List<ChunkPayload> chunk(
            CandidateCV candidateCV,
            String parsedText,
            TokenizerMode mode
    ) {
        // Validation
        if (parsedText == null || parsedText.isBlank()) {
            log.warn("Empty CV text for candidate: {}", candidateCV.getCandidateId());
            return Collections.emptyList();
        }

        try {
            // 1. Normalize text
            String normalized = textNormalizer.normalizeText(parsedText);

            // 2. Extract metadata
            CVMetadata metadata = metadataExtractor.extractAll(normalized);

            // 3. Generate summary
            String summary = summaryGenerator.generateDocumentSummary(normalized);
            metadata.setDocumentSummary(summary);

            // 4. Delegate to chunking strategy
            List<ChunkPayload> chunks = chunkingStrategy.chunk(
                    candidateCV,
                    normalized,
                    metadata
            );

            log.info("Successfully chunked CV into {} chunks", chunks.size());
            return chunks;

        } catch (Exception e) {
            log.error("Error chunking CV", e);
            // Graceful degradation
//            return List.of(buildFallbackChunk(candidateCV, parsedText));
        }
        return List.of();
    }

//    private ChunkPayload buildFallbackChunk(...) {
//        // Simple fallback
//    }

    /**
     * Helper class to track section boundaries during parsing.
     */
    public static class SectionBoundary {
        final int position;
        final String name;

        public SectionBoundary(int position, String name) {
            this.position = position;
            this.name = name;
        }
    }

    public enum TokenizerMode {
        ESTIMATE, REMOTE
    }
}
