package org.example.recruitmentservice.utils;

import lombok.RequiredArgsConstructor;
import org.example.recruitmentservice.services.chunking.config.ChunkingConfig;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TextUtils {
    private final ChunkingConfig config;

    public int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    public int estimateTokensFromWords(int words) {
        return (int) Math.ceil(words * config.getTokensPerWord());
    }

    public String truncateByWords(String text, int wordLimit) {
        if (text == null || text.isBlank()) return "";

        String[] words = text.split("\\s+");
        if (words.length <= wordLimit) return text;

        return String.join(" ", Arrays.copyOfRange(words, 0, wordLimit)) + "...";
    }

    public String normalizeHeading(String heading) {
        if (heading == null || heading.isEmpty()) return "";

        // Capitalize first letter of each word
        return Arrays.stream(heading.trim().split("\\s+"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
}
