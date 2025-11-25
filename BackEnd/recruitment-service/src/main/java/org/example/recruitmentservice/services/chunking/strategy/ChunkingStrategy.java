package org.example.recruitmentservice.services.chunking.strategy;

import org.example.recruitmentservice.dto.request.ChunkPayload;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.services.metadata.model.CVMetadata;

import java.util.List;

public interface ChunkingStrategy {
    List<ChunkPayload> chunk(
            CandidateCV candidateCV,
            String normalizedText,
            CVMetadata metadata
    );
}
