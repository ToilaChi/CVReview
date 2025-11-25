package org.example.recruitmentservice.services.summary;

import lombok.RequiredArgsConstructor;
import org.example.recruitmentservice.services.chunking.config.ChunkingConfig;
import org.example.recruitmentservice.utils.TextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ExtractiveSummaryGenerator implements SummaryGenerator {
    private final ChunkingConfig config;
    private final TextUtils textUtils;

    /**
     * Pattern for sentence splitting.
     * Handles common sentence terminators while avoiding abbreviations.
     */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
            "(?<=[.!?])\\s+(?=[A-Z])|\\n{2,}"
    );

    /**
     * Generates a document-level summary from full CV text.
     * Currently extractive; can be replaced with LLM-based summarization.
     */
    @Override
    public String generateDocumentSummary(String fullText) {
        return extractiveSummary(fullText, config.getDocumentSummaryMaxWords());
    }

    /**
     * Generates a section-level summary.
     */
    @Override
    public String generateSectionSummary(String sectionName, String sectionText) {
        return extractiveSummary(sectionText, config.getSectionSummaryMaxWords());
    }

    /**
     * Generates a chunk summary
     */
    @Override
    public String generateChunkSummary(String chunkText) {
        int maxWords = Math.min(
                config.getSectionSummaryMaxWords() / 2,
                50
        );
        return extractiveSummary(chunkText, maxWords);
    }


    /**
     * Simple extractive summarization: takes first N words.
     * TODO: Replace with LLM-based abstractive summarization for better quality.
     */
    private String extractiveSummary(String text, int maxWords) {
        if (text == null || text.isBlank()) {
            return "";
        }

        // Clean and normalize text first
        String cleaned = cleanText(text);

        // Split into sentences
        List<String> sentences = splitIntoMeaningfulSentences(cleaned);

        if (sentences.isEmpty()) {
            return truncateByWords(cleaned, maxWords);
        }

        // Select most informative sentences
        List<String> selectedSentences = selectInformativeSentences(sentences, maxWords);

        if (selectedSentences.isEmpty()) {
            return truncateByWords(sentences.get(0), maxWords);
        }

        return String.join(" ", selectedSentences);
    }

    /**
     * Clean CV text - remove headers, formatting, excessive whitespace
     */
    private String cleanText(String text) {
        return text
                // Remove markdown headers
                .replaceAll("^#+\\s+", "")
                // Remove table separators
                .replaceAll("\\|\\s*-+\\s*\\|", "")
                // Remove excessive pipes (tables)
                .replaceAll("\\|", " ")
                // Remove bullet points but keep content
                .replaceAll("^\\s*[•\\-*]\\s+", "")
                // Normalize whitespace
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Split into meaningful sentences (skip short fragments)
     */
    private List<String> splitIntoMeaningfulSentences(String text) {
        String[] rawSentences = SENTENCE_SPLIT.split(text);
        List<String> meaningful = new ArrayList<>();

        for (String sentence : rawSentences) {
            String trimmed = sentence.trim();

            // Skip empty or very short sentences (< 5 words)
            if (trimmed.isEmpty() || textUtils.countWords(trimmed) < 5) {
                continue;
            }

            // Skip sentences that are just names, dates, or formatting
            if (isMetadataLine(trimmed)) {
                continue;
            }

            meaningful.add(trimmed);
        }

        return meaningful;
    }

    /**
     * Detect metadata lines (names, dates, contact info)
     */
    private boolean isMetadataLine(String sentence) {
        // Patterns for common CV metadata
        return sentence.matches(".*\\d{2}/\\d{2}/\\d{4}.*")  // Dates
                || sentence.matches(".*\\+\\d{2}\\s*\\d+.*")  // Phone numbers
                || sentence.matches(".*@.*\\..*")             // Emails
                || sentence.matches("^[A-Z][a-z]+\\s+[A-Z][a-z]+.*") // Just names
                || sentence.length() < 20;  // Very short lines
    }

    /**
     * Select most informative sentences
     * Priority: sentences with skills, experience, achievements
     */
    private List<String> selectInformativeSentences(List<String> sentences, int maxWords) {
        List<String> selected = new ArrayList<>();
        int wordCount = 0;

        // Keywords that indicate important information
        String[] importantKeywords = {
                "experience", "proficient", "skilled", "developed", "built",
                "managed", "led", "achieved", "implemented", "designed",
                "years", "projects", "responsibilities"
        };

        // First pass: Select sentences with important keywords
        for (String sentence : sentences) {
            int sentenceWords = textUtils.countWords(sentence);

            if (wordCount + sentenceWords > maxWords) {
                break;
            }

            String lowerSentence = sentence.toLowerCase();
            boolean isImportant = false;

            for (String keyword : importantKeywords) {
                if (lowerSentence.contains(keyword)) {
                    isImportant = true;
                    break;
                }
            }

            if (isImportant || selected.isEmpty()) {  // Always include first sentence
                selected.add(sentence);
                wordCount += sentenceWords;
            }
        }

        // If no keywords found, just take first sentences
        if (selected.isEmpty()) {
            for (String sentence : sentences) {
                int sentenceWords = textUtils.countWords(sentence);
                if (wordCount + sentenceWords > maxWords) {
                    break;
                }
                selected.add(sentence);
                wordCount += sentenceWords;
            }
        }

        return selected;
    }

    /**
     * Truncate text by word count (fallback)
     */
    private String truncateByWords(String text, int maxWords) {
        String[] words = text.split("\\s+");
        if (words.length <= maxWords) {
            return text;
        }

        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) truncated.append(" ");
            truncated.append(words[i]);
        }
        truncated.append("...");

        return truncated.toString();
    }
}
