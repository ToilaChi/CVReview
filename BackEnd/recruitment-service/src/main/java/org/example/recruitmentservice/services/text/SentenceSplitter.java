package org.example.recruitmentservice.services.text;

import lombok.extern.slf4j.Slf4j;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import org.example.recruitmentservice.services.chunking.ChunkingService;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SentenceSplitter {
    /**
     * Pattern for detecting bullet points and list items.
     */
    private static final Pattern BULLET_POINT = Pattern.compile(
            "^\\s*[•●○◦▪▫-]\\s+"
    );

    /**
     * Pattern for sentence splitting.
     * Handles common sentence terminators while avoiding abbreviations.
     */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
            "(?<=[.!?])\\s+(?=[A-Z])|\\n{2,}"
    );

    private static SentenceDetectorME sentenceDetector;

    static {
        try (InputStream modelIn = ChunkingService.class
                .getResourceAsStream("/opennlp-en-sent-1.3-2.5.4.bin")) {
            SentenceModel model = new SentenceModel(modelIn);
            sentenceDetector = new SentenceDetectorME(model);
        } catch (Exception e) {
            log.warn("Failed to load sentence model, falling back to regex", e);
        }
    }

    /**
     * Splits text into sentences using Apache OpenNLP for better accuracy.
     * Handles abbreviations, decimals, and other edge cases.
     * Falls back to regex if NLP model unavailable.
     */
    public List<String> splitIntoSentences(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // Try NLP-based splitting first
        if (sentenceDetector != null) {
            try {
                String[] sentences = sentenceDetector.sentDetect(text);
                return Arrays.stream(sentences)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.debug("NLP sentence detection failed, using regex fallback", e);
            }
        }

        // Fallback to improved regex
        return Arrays.stream(SENTENCE_SPLIT.split(text))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // Helper methods
    /**
     * Checks if a sentence represents a semantic boundary (topic change).
     * Uses heuristics like bullet points, blank lines, or keyword transitions.
     */
    public boolean isSemanticBoundary(String sentence, String previousSentence) {
        if (sentence == null || previousSentence == null) return false;

        // Bullet point indicates new topic/item
        if (BULLET_POINT.matcher(sentence).find()) {
            return true;
        }

        // Date patterns indicate new time period (e.g., different jobs)
        if (containsDateRange(sentence) && containsDateRange(previousSentence)) {
            return true;
        }

        // Company/role change indicators
        Pattern roleChange = Pattern.compile(
                "\\b(at|@|worked for|joined|employed by)\\b.*\\b(company|corp|inc|ltd)\\b",
                Pattern.CASE_INSENSITIVE
        );
        if (roleChange.matcher(sentence).find()) {
            return true;
        }

        return false;
    }

    public boolean isBulletPoint(String text) {
        return BULLET_POINT.matcher(text).find();
    }

    private boolean containsDateRange(String text) {
        Pattern datePattern = Pattern.compile(
                "\\b(20\\d{2}|19\\d{2})\\b.*?[-–—].*?\\b(20\\d{2}|19\\d{2}|present|current)\\b",
                Pattern.CASE_INSENSITIVE
        );
        return datePattern.matcher(text).find();
    }
}
