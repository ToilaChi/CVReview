package org.example.recruitmentservice.services.chunking;

import org.example.recruitmentservice.dto.request.ChunkPayload;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.services.chunking.strategy.ChunkingStrategy;
import org.example.recruitmentservice.services.metadata.MetadataExtractor;
import org.example.recruitmentservice.services.metadata.model.CVMetadata;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {
    private final ChunkingStrategy chunkingStrategy;
    private final MetadataExtractor metadataExtractor;

    /**
     * Main entry point - simplified orchestration
     */
    public List<ChunkPayload> chunk(CandidateCV candidateCV, String parsedText) {
        if (candidateCV == null) {
            log.error("Candidate CV is null");
            return Collections.emptyList();
        }

        if (parsedText == null || parsedText.isBlank()) {
            log.warn("Empty CV text for candidate: {}", candidateCV.getCandidateId());
            return Collections.emptyList();
        }

        try {
            log.info("Starting chunking process for candidate: {}", candidateCV.getCandidateId());

            // Extract metadata (no normalization needed - done in SectionExtractor)
            CVMetadata metadata = metadataExtractor.extractAll(parsedText);
            log.debug("Extracted metadata: {} skills, {} years experience",
                    metadata.getSkills().size(),
                    metadata.getExperienceYears());

            List<ChunkPayload> chunks = chunkingStrategy.chunk(
                    candidateCV,
                    parsedText,
                    metadata
            );

            log.info("Successfully chunked CV into {} chunks for candidate: {}",
                    chunks.size(), candidateCV.getCandidateId());
            return chunks;

        } catch (Exception e) {
            log.error("Error chunking CV for candidate: {}", candidateCV.getCandidateId(), e);
            return Collections.emptyList();
        }
    }
}