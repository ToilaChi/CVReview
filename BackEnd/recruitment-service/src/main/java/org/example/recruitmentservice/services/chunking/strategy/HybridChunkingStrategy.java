package org.example.recruitmentservice.services.chunking.strategy;


import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.dto.request.ChunkPayload;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.services.chunking.config.ChunkingConfig;
import org.example.recruitmentservice.services.metadata.model.CVMetadata;
import org.example.recruitmentservice.services.text.EntityExtractor;
import org.example.recruitmentservice.services.text.EntityExtractor.Entity;
import org.example.recruitmentservice.services.text.SectionExtractor;
import org.example.recruitmentservice.services.text.SentenceSplitter;
import org.example.recruitmentservice.utils.TextUtils;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Hybrid chunking strategy:
 * - Section-based: Split by markdown headers
 * - Entity-based: Each project/job/education = chunk(s)
 * - Sentence-based: Fallback for unstructured sections
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridChunkingStrategy implements ChunkingStrategy {

    private final ChunkingConfig config;
    private final SectionExtractor sectionExtractor;
    private final EntityExtractor entityExtractor;
    private final SentenceSplitter sentenceSplitter;
    private final TextUtils textUtils;

    @Override
    public List<ChunkPayload> chunk(CandidateCV candidateCV, String normalizedText, CVMetadata metadata) {
        if (normalizedText == null || normalizedText.isBlank()) {
            log.warn("Empty or null CV text for candidate: {}", candidateCV.getCandidateId());
            return Collections.emptyList();
        }

        try {
            List<ChunkPayload> result = new ArrayList<>();
            int globalIndex = 0;

            // ALWAYS create FullText chunk first (requirement)
            ChunkPayload fullTextChunk = buildPayload(
                    candidateCV, "FullText", globalIndex++, normalizedText, metadata
            );
            result.add(fullTextChunk);
            log.debug("Created mandatory FullText chunk ({} words)", fullTextChunk.getWords());

            // Check if entire CV is too short to further chunk
            int totalWords = textUtils.countWords(normalizedText);
            int totalTokens = textUtils.estimateTokensFromWords(totalWords);

            if (totalTokens < config.getMinTokens()) {
                log.info("CV too short ({} tokens), returning only FullText chunk", totalTokens);
                return result;
            }

            // Extract sections by markdown headers
            Map<String, String> sections = sectionExtractor.extractSections(normalizedText);

            // Check if section extraction failed (only 'FullText' returned)
            boolean extractionFailed = sections.size() == 1 && sections.containsKey("FullText");
            if (extractionFailed) {
                log.warn("Section extraction failed. Attempting entity-based extraction as fallback.");
            }

            // Process each section
            for (Map.Entry<String, String> entry : sections.entrySet()) {
                String sectionName = entry.getKey();
                String sectionText = entry.getValue();

                if (sectionText.isBlank()) continue;

                log.debug("Processing section '{}' ({} chars)", sectionName, sectionText.length());

                // If extraction failed (FullText), try entity extraction from entire CV
                List<Entity> entities = new ArrayList<>();
                if (extractionFailed) {
                    log.debug("Attempting aggressive entity extraction on 'FullText' fallback");
                    entities = attemptAllEntityExtractions(normalizedText);

                    if (!entities.isEmpty()) {
                        log.info("Successfully extracted {} entities from FullText fallback", entities.size());
                        for (Entity entity : entities) {
                            List<ChunkPayload> entityChunks = chunkEntity(
                                    candidateCV, entity.getType(), entity, globalIndex, metadata
                            );
                            result.addAll(entityChunks);
                            globalIndex += entityChunks.size();
                        }
                        continue;
                    } else {
                        log.debug("Entity extraction on FullText fallback returned no entities");
                    }
                }

                // Try normal entity-based chunking for structured sections
                if (config.shouldChunkByEntity(sectionName)) {
                    entities = entityExtractor.extractEntities(sectionName, sectionText);

                    if (!entities.isEmpty() && !entities.get(0).getType().equals("SECTION")) {
                        log.debug("Using entity-based chunking for '{}' ({} entities)",
                                sectionName, entities.size());

                        for (Entity entity : entities) {
                            List<ChunkPayload> entityChunks = chunkEntity(
                                    candidateCV, sectionName, entity, globalIndex, metadata
                            );
                            result.addAll(entityChunks);
                            globalIndex += entityChunks.size();
                        }
                        continue;
                    }
                }

                // For non-entity sections, create single chunk per section
                // (Don't split by sentences to preserve semantic units)
                int sectionWords = textUtils.countWords(sectionText);
                int sectionTokens = textUtils.estimateTokensFromWords(sectionWords);

                if (sectionTokens <= config.getMaxTokens()) {
                    // Section fits in one chunk - keep it intact
                    ChunkPayload sectionChunk = buildPayload(
                            candidateCV, sectionName, globalIndex++, sectionText, metadata
                    );
                    result.add(sectionChunk);
                    log.debug("Created single chunk for section '{}' ({} tokens)",
                            sectionName, sectionTokens);
                } else {
                    // Section too large - need to split carefully
                    log.warn("Section '{}' exceeds max tokens ({} tokens), attempting careful split",
                            sectionName, sectionTokens);
                    List<ChunkPayload> sectionChunks = splitLargeSection(
                            candidateCV, sectionName, sectionText, globalIndex, metadata
                    );
                    result.addAll(sectionChunks);
                    globalIndex += sectionChunks.size();
                }
            }

            log.info("Successfully chunked CV into {} chunks for candidate: {}",
                    result.size(), candidateCV.getCandidateId());
            return result;

        } catch (Exception e) {
            log.error("Error chunking CV for candidate: {}", candidateCV.getCandidateId(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Attempt ALL entity extraction strategies on the full text.
     * Used as aggressive fallback when section extraction completely fails.
     */
    private List<Entity> attemptAllEntityExtractions(String fullText) {
        List<Entity> allEntities = new ArrayList<>();

        // Try Projects
        List<Entity> projects = entityExtractor.extractEntities("PROJECTS", fullText);
        if (!projects.isEmpty() && !projects.get(0).getType().equals("SECTION")) {
            allEntities.addAll(projects);
            log.debug("Extracted {} projects from full text", projects.size());
        }

        // Try Experience
        List<Entity> jobs = entityExtractor.extractEntities("EXPERIENCE", fullText);
        if (!jobs.isEmpty() && !jobs.get(0).getType().equals("SECTION")) {
            allEntities.addAll(jobs);
            log.debug("Extracted {} jobs from full text", jobs.size());
        }

        // Try Education
        List<Entity> education = entityExtractor.extractEntities("EDUCATION", fullText);
        if (!education.isEmpty() && !education.get(0).getType().equals("SECTION")) {
            allEntities.addAll(education);
            log.debug("Extracted {} education entries from full text", education.size());
        }

        return allEntities;
    }

    /**
     * Chunk a single entity (project, job, education)
     * Keeps entity intact as single chunk to preserve semantic meaning
     */
    private List<ChunkPayload> chunkEntity(
            CandidateCV candidateCV,
            String sectionName,
            Entity entity,
            int startIndex,
            CVMetadata metadata) {

        int tokens = textUtils.estimateTokensFromWords(textUtils.countWords(entity.getContent()));

        // Keep entity as single chunk (don't split semantic units)
        ChunkPayload chunk = buildPayload(
                candidateCV, sectionName, startIndex, entity.getContent(), metadata
        );

        log.debug("Created chunk for entity '{}' ({} tokens)", entity.getTitle(), tokens);

        if (tokens > config.getMaxTokens()) {
            log.warn("Entity '{}' exceeds max tokens ({} > {}), but keeping intact to preserve semantic meaning",
                    entity.getTitle(), tokens, config.getMaxTokens());
        }

        return List.of(chunk);
    }

    /**
     * Split large section only when absolutely necessary
     * Tries to split at semantic boundaries (paragraphs, bullet lists)
     */
    private List<ChunkPayload> splitLargeSection(
            CandidateCV candidateCV,
            String sectionName,
            String sectionText,
            int startIndex,
            CVMetadata metadata) {

        List<ChunkPayload> chunks = new ArrayList<>();

        // First attempt: Split by double newlines (paragraphs)
        String[] paragraphs = sectionText.split("\n\n+");

        if (paragraphs.length > 1) {
            log.debug("Splitting section '{}' by paragraphs ({} paragraphs)",
                    sectionName, paragraphs.length);

            StringBuilder currentChunk = new StringBuilder();
            int currentWords = 0;
            int chunkIdx = startIndex;

            for (String paragraph : paragraphs) {
                paragraph = paragraph.trim();
                if (paragraph.isEmpty()) continue;

                int paraWords = textUtils.countWords(paragraph);
                int paraTokens = textUtils.estimateTokensFromWords(paraWords);
                int currentTokens = textUtils.estimateTokensFromWords(currentWords);

                // Can we add this paragraph to current chunk?
                if (currentTokens + paraTokens <= config.getMaxTokens()) {
                    if (!currentChunk.isEmpty()) {
                        currentChunk.append("\n\n");
                    }
                    currentChunk.append(paragraph);
                    currentWords += paraWords;
                } else {
                    // Finalize current chunk
                    if (!currentChunk.isEmpty()) {
                        chunks.add(buildPayload(
                                candidateCV, sectionName, chunkIdx++,
                                currentChunk.toString(), metadata
                        ));
                    }

                    // Start new chunk with current paragraph
                    currentChunk = new StringBuilder(paragraph);
                    currentWords = paraWords;
                }
            }

            // Add remaining content
            if (!currentChunk.isEmpty()) {
                chunks.add(buildPayload(
                        candidateCV, sectionName, chunkIdx,
                        currentChunk.toString(), metadata
                ));
            }

            return chunks;
        }

        // Second attempt: Split by single newlines (bullet points / lines)
        String[] lines = sectionText.split("\n");

        if (lines.length > 1) {
            log.debug("Splitting section '{}' by lines ({} lines)",
                    sectionName, lines.length);

            StringBuilder currentChunk = new StringBuilder();
            int currentWords = 0;
            int chunkIdx = startIndex;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                int lineWords = textUtils.countWords(line);
                int lineTokens = textUtils.estimateTokensFromWords(lineWords);
                int currentTokens = textUtils.estimateTokensFromWords(currentWords);

                if (currentTokens + lineTokens <= config.getMaxTokens()) {
                    if (!currentChunk.isEmpty()) {
                        currentChunk.append("\n");
                    }
                    currentChunk.append(line);
                    currentWords += lineWords;
                } else {
                    // Finalize current chunk
                    if (!currentChunk.isEmpty()) {
                        chunks.add(buildPayload(
                                candidateCV, sectionName, chunkIdx++,
                                currentChunk.toString(), metadata
                        ));
                    }

                    // Start new chunk
                    currentChunk = new StringBuilder(line);
                    currentWords = lineWords;
                }
            }

            // Add remaining content
            if (!currentChunk.isEmpty()) {
                chunks.add(buildPayload(
                        candidateCV, sectionName, chunkIdx,
                        currentChunk.toString(), metadata
                ));
            }

            return chunks;
        }

        // Last resort: Keep as single large chunk (preserve semantic integrity)
        log.warn("Cannot split section '{}' without breaking semantic units, keeping as single chunk",
                sectionName);
        chunks.add(buildPayload(
                candidateCV, sectionName, startIndex, sectionText, metadata
        ));

        return chunks;
    }

    /**
     * Builds a chunk payload with all metadata.
     * NO SUMMARIES - only original text.
     */
    private ChunkPayload buildPayload(
            CandidateCV cv,
            String section,
            int chunkIdx,
            String text,
            CVMetadata metadata) {

        int words = textUtils.countWords(text);
        int tokens = textUtils.estimateTokensFromWords(words);

        return ChunkPayload.builder()
                .candidateId(cv.getCandidateId())
                .hrId(cv.getHrId())
                .position(cv.getPosition() != null ? cv.getPosition().getName() : null)
                .section(section)
                .chunkIndex(chunkIdx)
                .chunkText(text)  // ORIGINAL TEXT ONLY
                .words(words)
                .tokensEstimate(tokens)
                .email(cv.getEmail())
                .cvId(cv.getId())
                .cvStatus(cv.getCvStatus() != null ? cv.getCvStatus().name() : null)
                .sourceType(cv.getSourceType() != null ? cv.getSourceType().name() : null)
                .createdAt(LocalDateTime.now())
                .skills(metadata.getSkills())
                .experienceYears(metadata.getExperienceYears())
                .seniorityLevel(metadata.getSeniorityLevel())
                .companies(metadata.getCompanies())
                .degrees(metadata.getDegrees())
                .dateRanges(metadata.getDateRanges())
                // NO documentSummary, sectionSummary, chunkSummary fields
                .build();
    }
}
