//package org.example.recruitmentservice.services.summary;
//
//
//import lombok.RequiredArgsConstructor;
//import org.example.recruitmentservice.services.chunking.config.ChunkingConfig;
//import org.example.recruitmentservice.utils.TextUtils;
//import org.springframework.stereotype.Component;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.regex.Pattern;
//
///**
// * Extractive summarization that preserves complete sentences.
// * No "..." truncation - only complete sentences are included.
// */
//@Component
//@RequiredArgsConstructor
//public class ExtractiveSummaryGenerator implements SummaryGenerator {
//    private final ChunkingConfig config;
//    private final TextUtils textUtils;
//
//    /**
//     * Pattern for sentence splitting.
//     */
//    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
//            "(?<=[.!?])\\s+(?=[A-Z])|\\n{2,}"
//    );
//
//    /**
//     * Generates a document-level summary from full CV text.
//     */
//    @Override
//    public String generateDocumentSummary(String fullText) {
//        return extractiveSummary(fullText, config.getDocumentSummaryMaxWords());
//    }
//
//    /**
//     * Generates a section-level summary.
//     */
//    @Override
//    public String generateSectionSummary(String sectionName, String sectionText) {
//        return extractiveSummary(sectionText, config.getSectionSummaryMaxWords());
//    }
//
//    /**
//     * Generates a chunk summary with increased limit
//     */
//    @Override
//    public String generateChunkSummary(String chunkText) {
//        // Use config limit (now 100 words instead of 50)
//        return extractiveSummary(chunkText, config.getChunkSummaryMaxWords());
//    }
//
//    /**
//     * Simple extractive summarization: takes first complete sentences up to maxWords.
//     * FIXED: Now preserves complete sentences without '...'
//     */
//    private String extractiveSummary(String text, int maxWords) {
//        if (text == null || text.isBlank()) {
//            return "";
//        }
//
//        // Clean and normalize text first
//        String cleaned = cleanText(text);
//
//        // Split into sentences
//        List<String> sentences = splitIntoMeaningfulSentences(cleaned);
//        if (sentences.isEmpty()) {
//            return truncateBySentences(cleaned, maxWords);
//        }
//
//        // Select most informative sentences
//        List<String> selectedSentences = selectInformativeSentences(sentences, maxWords);
//        if (selectedSentences.isEmpty()) {
//            return truncateBySentences(sentences.get(0), maxWords);
//        }
//
//        return String.join(" ", selectedSentences);
//    }
//
//    /**
//     * Clean CV text - remove headers, formatting, excessive whitespace
//     */
//    private String cleanText(String text) {
//        return text
//                // Remove markdown headers
//                .replaceAll("^#+\\s+", "")
//                // Remove table separators
//                .replaceAll("\\|\\s*-+\\s*\\|", "")
//                // Remove excessive pipes (tables)
//                .replaceAll("\\|", " ")
//                // Remove bullet points but keep content
//                .replaceAll("^\\s*[•\\-*]\\s+", "")
//                // Normalize whitespace
//                .replaceAll("\\s+", " ")
//                .trim();
//    }
//
//    /**
//     * Split into meaningful sentences (skip short fragments)
//     */
//    private List<String> splitIntoMeaningfulSentences(String text) {
//        String[] rawSentences = SENTENCE_SPLIT.split(text);
//        List<String> meaningful = new ArrayList<>();
//
//        for (String sentence : rawSentences) {
//            String trimmed = sentence.trim();
//
//            // Skip empty or very short sentences (< 5 words)
//            if (trimmed.isEmpty() || textUtils.countWords(trimmed) < 5) {
//                continue;
//            }
//
//            // Skip sentences that are just names, dates, or formatting
//            if (isMetadataLine(trimmed)) {
//                continue;
//            }
//
//            meaningful.add(trimmed);
//        }
//
//        return meaningful;
//    }
//
//    /**
//     * Detect metadata lines (names, dates, contact info)
//     */
//    private boolean isMetadataLine(String sentence) {
//        // Patterns for common CV metadata
//        return sentence.matches(".*\\d{2}/\\d{2}/\\d{4}.*") // Dates
//                || sentence.matches(".*\\+\\d{2}\\s*\\d+.*") // Phone numbers
//                || sentence.matches(".*@.*\\..*") // Emails
//                || sentence.matches("^[A-Z][a-z]+\\s+[A-Z][a-z]+.*") // Just names
//                || sentence.length() < 20; // Very short lines
//    }
//
//    /**
//     * Select most informative sentences
//     * Priority: sentences with skills, experience, achievements
//     */
//    private List<String> selectInformativeSentences(List<String> sentences, int maxWords) {
//        List<String> selected = new ArrayList<>();
//        int wordCount = 0;
//
//        // Keywords that indicate important information
//        String[] importantKeywords = {
//                "experience", "proficient", "skilled", "developed", "built",
//                "managed", "led", "achieved", "implemented", "designed",
//                "years", "projects", "responsibilities", "knowledge", "expertise"
//        };
//
//        // First pass: Select sentences with important keywords
//        for (String sentence : sentences) {
//            int sentenceWords = textUtils.countWords(sentence);
//            if (wordCount + sentenceWords > maxWords) {
//                break;
//            }
//
//            String lowerSentence = sentence.toLowerCase();
//            boolean isImportant = false;
//            for (String keyword : importantKeywords) {
//                if (lowerSentence.contains(keyword)) {
//                    isImportant = true;
//                    break;
//                }
//            }
//
//            if (isImportant || selected.isEmpty()) { // Always include first sentence
//                selected.add(sentence);
//                wordCount += sentenceWords;
//            }
//        }
//
//        // If no keywords found, just take first sentences
//        if (selected.isEmpty()) {
//            for (String sentence : sentences) {
//                int sentenceWords = textUtils.countWords(sentence);
//                if (wordCount + sentenceWords > maxWords) {
//                    break;
//                }
//
//                selected.add(sentence);
//                wordCount += sentenceWords;
//            }
//        }
//
//        return selected;
//    }
//
//    /**
//     * Truncate text by complete sentences (NO '...' added!)
//     * FIXED: Returns complete sentences only, no mid-sentence cuts
//     */
//    private String truncateBySentences(String text, int maxWords) {
//        String[] words = text.split("\\s+");
//        if (words.length <= maxWords) {
//            return text;
//        }
//
//        // Split into sentences
//        String[] sentences = SENTENCE_SPLIT.split(text);
//
//        // Find last complete sentence within limit
//        StringBuilder result = new StringBuilder();
//        int wordCount = 0;
//
//        for (String sentence : sentences) {
//            String trimmed = sentence.trim();
//            if (trimmed.isEmpty()) continue;
//
//            int sentenceWords = textUtils.countWords(trimmed);
//
//            // Check if adding this sentence exceeds limit
//            if (wordCount + sentenceWords <= maxWords) {
//                if (wordCount > 0) result.append(" ");
//                result.append(trimmed);
//                wordCount += sentenceWords;
//            } else {
//                // Don't add partial sentence - stop here
//                break;
//            }
//        }
//
//        // Return complete sentences WITHOUT '...'
//        // If result is empty, take first sentence even if it exceeds limit
//        if (result.isEmpty() && sentences.length > 0) {
//            return sentences[0].trim();
//        }
//
//        return result.toString();
//    }
//}