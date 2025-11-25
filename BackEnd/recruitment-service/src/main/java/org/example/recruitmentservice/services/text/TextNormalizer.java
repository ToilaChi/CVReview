package org.example.recruitmentservice.services.text;

import org.springframework.stereotype.Component;

@Component
public class TextNormalizer {
    public String normalizeText(String text) {
        if (text == null) return "";

        return text
                .replaceAll("<[^>]+>", " ")              // Remove HTML tags
                .replaceAll("[\\x00-\\x1F\\x7F]", " ")  // Remove control characters
                .replaceAll("\\s+", " ")                 // Collapse whitespace
                .trim();
    }
}
