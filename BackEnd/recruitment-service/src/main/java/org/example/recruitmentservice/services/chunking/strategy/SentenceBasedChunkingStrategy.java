//package org.example.recruitmentservice.services.chunking.strategy;
//
//import lombok.extern.slf4j.Slf4j;
//import org.example.recruitmentservice.dto.request.ChunkPayload;
//import org.example.recruitmentservice.models.entity.CandidateCV;
//import org.example.recruitmentservice.services.chunking.config.ChunkingConfig;
//import org.example.recruitmentservice.services.metadata.model.CVMetadata;
//import org.example.recruitmentservice.services.summary.SummaryGenerator;
//import org.example.recruitmentservice.services.text.SectionExtractor;
//import org.example.recruitmentservice.services.text.SentenceSplitter;
//import org.example.recruitmentservice.utils.TextUtils;
//import org.springframework.stereotype.Component;
//import lombok.RequiredArgsConstructor;
//
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//import java.util.Map;
//
//@Component
//@RequiredArgsConstructor
//@Slf4j
//public class SentenceBasedChunkingStrategy implements ChunkingStrategy {
//
//    private final ChunkingConfig config;
//    private final SectionExtractor sectionExtractor;
//    private final SentenceSplitter sentenceSplitter;
//    private final SummaryGenerator summaryGenerator;
//
//    private final TextUtils textUtils;
//
//    @Override
//    public List<ChunkPayload> chunk(CandidateCV candidateCV, String normalizedText, CVMetadata metadata) {
//        if (normalizedText == null || normalizedText.isBlank()) {
//            log.warn("Empty or null CV text for candidate: {}", candidateCV.getCandidateId());
//            return Collections.emptyList();
//        }
//
//        try {
//            // Extract document summary
//            String documentSummary = metadata.getDocumentSummary();
//
//            // Check if entire CV is too short to chunk
//            int totalWords = textUtils.countWords(normalizedText);
//            int totalTokens = textUtils.estimateTokensFromWords(totalWords);
//
//            if (totalTokens < config.getMinTokens()) {
//                log.info("CV too short, returning single chunk");
//
//                // Single chunk = document summary = section summary = chunk summary
//                return List.of(buildPayload(
//                        candidateCV, "FullText", 0, normalizedText,
//                        metadata,
//                        documentSummary,
//                        documentSummary,
//                        documentSummary
//                ));
//            }
//
//            // Extract sections and chunk each one
//            Map<String, String> sections = sectionExtractor.extractSections(normalizedText);
//            List<ChunkPayload> result = new ArrayList<>();
//            int globalIndex = 0;
//
//            for (Map.Entry<String, String> entry : sections.entrySet()) {
//                String sectionName = entry.getKey();
//                String sectionText = entry.getValue();
//
//                if (sectionText.isBlank()) continue;
//
//                String sectionSummary = summaryGenerator.generateSectionSummary(
//                        sectionName,
//                        sectionText
//                );
//                List<String> sentences = sentenceSplitter.splitIntoSentences(sectionText);
//
//                List<ChunkPayload> chunks = buildChunksFromSentences(
//                        candidateCV, sectionName, sentences, globalIndex,
//                        metadata, documentSummary, sectionSummary
//                );
//
//                result.addAll(chunks);
//                globalIndex += chunks.size();
//            }
//
//            log.info("Successfully chunked CV into {} chunks for candidate: {}",
//                    result.size(), candidateCV.getCandidateId());
//            return result;
//
//        } catch (Exception e) {
//            log.error("Error chunking CV for candidate: {}", candidateCV.getCandidateId(), e);
//            return List.of(buildPayload(
//                    candidateCV, "FullText", 0, normalizedText,
//                    CVMetadata.empty(), "", "", ""
//            ));
//        }
//    }
//
//    /**
//     * Builds chunks from sentences with smart token-aware splitting and overlap.
//     */
//    private List<ChunkPayload> buildChunksFromSentences(
//            CandidateCV candidateCV,
//            String section,
//            List<String> sentences,
//            int startIndex,
//            CVMetadata metadata,
//            String documentSummary,
//            String sectionSummary
//    ) {
//        List<ChunkPayload> chunks = new ArrayList<>();
//        if (sentences.isEmpty()) return chunks;
//
//        List<String> currentSentences = new ArrayList<>();
//        int currentWords = 0;
//        int chunkIdx = startIndex;
//
//        for (int i = 0; i < sentences.size(); i++) {
//            String sentence = sentences.get(i);
//            int sentenceWords = textUtils.countWords(sentence);
//            int sentenceTokens = textUtils.estimateTokensFromWords(sentenceWords);
//            int currentTokens = textUtils.estimateTokensFromWords(currentWords);
//
//            // Can we add this sentence to current chunk?
//            boolean canAddSentence = currentTokens + sentenceTokens <= config.getMaxTokens();
//            boolean shouldKeepTogether = !currentSentences.isEmpty()
//                    && !sentenceSplitter.isSemanticBoundary(sentence, currentSentences.get(currentSentences.size() - 1));
//
//            if (canAddSentence || (shouldKeepTogether && currentTokens + sentenceTokens <= config.getMaxTokens() * 1.15)) {
//                currentSentences.add(sentence);
//                currentWords += sentenceWords;
//            } else {
//                // Current chunk is full
//                if (currentSentences.isEmpty()) {
//                    String truncated = textUtils.truncateByWords(sentence, (int)(config.getMaxTokens() / config.getTokensPerWord()));
//                    String chunkSummary = summaryGenerator.generateChunkSummary(truncated);
//
//                    chunks.add(buildPayload(
//                            candidateCV, section, chunkIdx++, truncated,
//                            metadata, documentSummary, sectionSummary, chunkSummary
//                    ));
//                } else {
//                    // Finalize current chunk
//                    String chunkText = String.join(" ", currentSentences);
//                    String chunkSummary = summaryGenerator.generateChunkSummary(chunkText);
//
//                    chunks.add(buildPayload(
//                            candidateCV, section, chunkIdx++, chunkText,
//                            metadata, documentSummary, sectionSummary, chunkSummary
//                    ));
//
//                    // Prepare overlap for next chunk
//                    List<String> overlap = getOverlapSentences(currentSentences, config.getOverlapTokens());
//                    currentSentences = new ArrayList<>(overlap);
//                    currentWords = overlap.stream()
//                            .mapToInt(textUtils::countWords)
//                            .sum();
//
//                    // Retry adding current sentence
//                    i--;
//                }
//            }
//        }
//
//        // Finalize remaining sentences
//        if (!currentSentences.isEmpty()) {
//            String chunkText = String.join(" ", currentSentences);
//            int tokens = textUtils.estimateTokensFromWords(textUtils.countWords(chunkText));
//
//            if (tokens >= config.getMinTokens() || chunks.isEmpty()) {
//                String chunkSummary = summaryGenerator.generateChunkSummary(chunkText);
//
//                chunks.add(buildPayload(
//                        candidateCV, section, chunkIdx, chunkText,
//                        metadata, documentSummary, sectionSummary, chunkSummary
//                ));
//            } else {
//                // Merge with previous chunk if too small
//                ChunkPayload lastChunk = chunks.get(chunks.size() - 1);
//                String mergedText = lastChunk.getChunkText() + " " + chunkText;
//                String mergedChunkSummary = summaryGenerator.generateChunkSummary(mergedText);
//
//                chunks.set(chunks.size() - 1,
//                        lastChunk.toBuilder()
//                                .chunkText(mergedText)
//                                .chunkSummary(mergedChunkSummary)
//                                .build());
//            }
//        }
//
//        return chunks;
//    }
//
//    /**
//     * Builds a chunk payload with all metadata.
//     */
//    private ChunkPayload buildPayload(
//            CandidateCV cv,
//            String section,
//            int chunkIdx,
//            String text,
//            CVMetadata metadata,
//            String documentSummary,
//            String sectionSummary,
//            String chunkSummary
//    ) {
//        int words = textUtils.countWords(text);
//        int tokens = textUtils.estimateTokensFromWords(words);
//
//        return ChunkPayload.builder()
//                .candidateId(cv.getCandidateId())
//                .hrId(cv.getHrId())
//                .position(cv.getPosition() != null ? cv.getPosition().getName() : null)
//                .section(section)
//                .chunkIndex(chunkIdx)
//                .chunkText(text)
//                .words(words)
//                .tokensEstimate(tokens)
//                .email(cv.getEmail())
//                .cvId(cv.getId())
//                .cvStatus(cv.getCvStatus() != null ? cv.getCvStatus().name() : null)
//                .sourceType(cv.getSourceType() != null ? cv.getSourceType().name() : null)
//                .createdAt(LocalDateTime.now())
//                .skills(metadata.getSkills())
//                .experienceYears(metadata.getExperienceYears())
//                .seniorityLevel(metadata.getSeniorityLevel())
//                .companies(metadata.getCompanies())
//                .degrees(metadata.getDegrees())
//                .dateRanges(metadata.getDateRanges())
//                .documentSummary(documentSummary)
//                .sectionSummary(sectionSummary)
//                .chunkSummary(chunkSummary)
//                .build();
//    }
//
//
//    /**
//     * Gets overlapping sentences with semantic awareness.
//     * Tries to include complete thoughts/bullets in overlap.
//     */
//    private List<String> getOverlapSentences(List<String> sentences, int overlapTokens) {
//        if (sentences.isEmpty()) return Collections.emptyList();
//
//        List<String> overlap = new ArrayList<>();
//        int tokens = 0;
//
//        for (int i = sentences.size() - 1; i >= 0; i--) {
//            String sentence = sentences.get(i);
//            int sentenceTokens = textUtils.estimateTokensFromWords(textUtils.countWords(sentence));
//
//            boolean isBulletStart = sentenceSplitter.isBulletPoint(sentence);
//
//            overlap.add(0, sentence);
//            tokens += sentenceTokens;
//
//            if (isBulletStart && tokens >= overlapTokens * 0.7) {
//                break;
//            }
//
//            if (tokens >= overlapTokens) break;
//        }
//
//        return overlap;
//    }
//}
