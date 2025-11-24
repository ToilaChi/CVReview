package org.example.recruitmentservice.services;

import org.example.recruitmentservice.dto.request.ChunkPayload;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

@Service
public class ChunkingService {
    private final int minTokens = 150;
    private final int maxTokens = 350;
    private final int overlapTokens = 30;
    private final double tokensPerWord = 1.33;

    private static final Pattern SECTION_HEADING = Pattern.compile(
            "(?im)^\\s*" +
                    "([#•●○▪▫-]|\\d+\\.?|[IVX]+\\.)?\\s*" +
                    "(experience|work experience|professional experience|employment history|career history|" +
                    "skills|technical skills|core competencies|expertise|" +
                    "projects?|work samples|portfolio|" +
                    "education|academic background|qualifications|" +
                    "summary|profile|about( me)?|objective|" +
                    "kinh nghiệm|kỹ năng|dự án|học vấn)" +
                    "[:\\s-]*$"
    );

    /**
     * Main chunking method - takes full CandidateCV entity for metadata
     */
    public List<ChunkPayload> chunk(CandidateCV candidateCV, String parsedText, TokenizerMode mode) {
        if (parsedText == null || parsedText.isBlank()) {
            return Collections.emptyList();
        }

        String normalized = normalizeText(parsedText);

        // Check if entire CV is too short
        int totalWords = countWords(normalized);
        int totalTokens = estimateTokensFromWords(totalWords);

        if (totalTokens < minTokens) {
            // Return single chunk for short CVs
            return List.of(buildPayload(candidateCV, "FullText", 0, normalized));
        }

        Map<String, String> sections = extractSections(normalized);
        List<ChunkPayload> result = new ArrayList<>();
        int globalIndex = 0;

        for (Map.Entry<String, String> entry : sections.entrySet()) {
            String sectionName = entry.getKey();
            String sectionText = entry.getValue();

            if (sectionText.isBlank()) continue;

            List<String> sentences = splitIntoSentences(sectionText);
            List<ChunkPayload> chunks = buildChunksFromSentences(
                    candidateCV, sectionName, sentences, mode, globalIndex
            );
            result.addAll(chunks);
            globalIndex += chunks.size();
        }

        return result;
    }

    private String normalizeText(String text) {
        if (text == null) return "";

        return text
                .replaceAll("<[^>]+>", " ")           // Remove HTML tags
                .replaceAll("[\\x00-\\x1F\\x7F]", "") // Remove control characters
                .replaceAll("\\s+", " ")              // Collapse whitespace
                .trim();
    }

    private Map<String, String> extractSections(String text) {
        Map<String, String> sections = new LinkedHashMap<>();
        Matcher m = SECTION_HEADING.matcher(text);
        List<Integer> headingPositions = new ArrayList<>();
        List<String> headingNames = new ArrayList<>();

        while (m.find()) {
            headingPositions.add(m.start());
            String name = m.group(2);
            if (name != null) {
                headingNames.add(name.toLowerCase().trim());
            }
        }

        if (headingPositions.isEmpty()) {
            sections.put("FullText", text);
            return sections;
        }

        // Build sections between headings
        for (int i = 0; i < headingPositions.size(); i++) {
            int start = headingPositions.get(i);
            int end = (i + 1 < headingPositions.size())
                    ? headingPositions.get(i + 1)
                    : text.length();
            String name = headingNames.get(i);

            // Extract content after heading
            String block = text.substring(start, end);
            // Remove heading line
            block = block.replaceFirst("(?s)^.*?\\n", "").trim();

            if (!block.isEmpty()) {
                sections.put(capitalize(name), block);
            }
        }

        return sections;
    }

    private List<String> splitIntoSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        // Split by sentence terminators and newlines
        String[] parts = text.split("(?<=[.?!])\\s+|\\n+");

        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private List<ChunkPayload> buildChunksFromSentences(
            CandidateCV candidateCV,
            String section,
            List<String> sentences,
            TokenizerMode mode,
            int startIndex
    ) {
        List<ChunkPayload> chunks = new ArrayList<>();
        if (sentences.isEmpty()) return chunks;

        List<String> current = new ArrayList<>();
        int currentWords = 0;
        int chunkIdx = startIndex;

        for (int i = 0; i < sentences.size(); i++) {
            String s = sentences.get(i);
            int sWords = countWords(s);
            int sTokensEstimate = estimateTokensFromWords(sWords);
            int currentTokensEstimate = estimateTokensFromWords(currentWords);

            if (currentTokensEstimate + sTokensEstimate <= maxTokens) {
                current.add(s);
                currentWords += sWords;
            } else {
                if (current.isEmpty()) {
                    // Sentence alone exceeds maxTokens -> truncate
                    String truncated = truncateByWords(s, (int)(maxTokens / tokensPerWord));
                    chunks.add(buildPayload(candidateCV, section, chunkIdx++, truncated));
                } else {
                    // Finalize current chunk
                    String chunkText = String.join(" ", current).trim();
                    chunks.add(buildPayload(candidateCV, section, chunkIdx++, chunkText));

                    // Prepare next chunk with overlap
                    List<String> overlap = getOverlap(current, overlapTokens);
                    current = new ArrayList<>(overlap);
                    currentWords = overlap.stream()
                            .mapToInt(this::countWords)
                            .sum();

                    // Reprocess current sentence
                    i--;
                }
            }
        }

        // Push leftover
        if (!current.isEmpty()) {
            String chunkText = String.join(" ", current).trim();
            chunks.add(buildPayload(candidateCV, section, chunkIdx++, chunkText));
        }

        return chunks;
    }

    private ChunkPayload buildPayload(
            CandidateCV cv,
            String section,
            int chunkIdx,
            String text
    ) {
        int words = countWords(text);
        int tokens = estimateTokensFromWords(words);

        return ChunkPayload.builder()
                .candidateId(cv.getCandidateId())
                .hrId(cv.getHrId())
                .position(cv.getPosition() != null ? cv.getPosition().getName() : "Unknown")
                .section(section)
                .chunkIndex(chunkIdx)
                .chunkText(text)
                .words(words)
                .tokensEstimate(tokens)
                // Metadata for RAG
                .email(cv.getEmail())
                .cvId(cv.getId())
                .cvStatus(cv.getCvStatus() != null ? cv.getCvStatus().name() : null)
                .sourceType(cv.getSourceType() != null ? cv.getSourceType().name() : null)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private int countWords(String s) {
        if (s == null || s.isBlank()) return 0;
        return s.trim().split("\\s+").length;
    }

    private int estimateTokensFromWords(int words) {
        return (int) Math.ceil(words * tokensPerWord);
    }

    private List<String> getOverlap(List<String> sentences, int overlapTokens) {
        List<String> out = new ArrayList<>();
        int tokens = 0;

        for (int i = sentences.size() - 1; i >= 0; i--) {
            String s = sentences.get(i);
            int sTokens = estimateTokensFromWords(countWords(s));

            out.add(0, s);
            tokens += sTokens;

            if (tokens >= overlapTokens) break;
        }

        return out;
    }

    private String truncateByWords(String s, int wordLimit) {
        String[] words = s.split("\\s+");
        if (words.length <= wordLimit) return s;
        return String.join(" ", Arrays.copyOfRange(words, 0, wordLimit));
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public enum TokenizerMode {
        ESTIMATE,  // Use word-based estimation
        REMOTE     // Call external tokenizer API (future)
    }
}