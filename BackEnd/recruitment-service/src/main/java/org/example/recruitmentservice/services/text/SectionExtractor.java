package org.example.recruitmentservice.services.text;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.services.chunking.ChunkingService;
import org.example.recruitmentservice.utils.TextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class SectionExtractor {
    private final TextUtils textUtils;

    /**
     * Extracts CV sections based on markdown headers.
     * Returns a map of normalized section names to their content.
     */
    public Map<String, String> extractSections(String text) {
        Map<String, String> sections = new LinkedHashMap<>();
        log.info("TEXT:\n{}", text);

        // Flexible pattern for markdown headers
        // Matches: # Header, ## Header, ### Header (with optional whitespace)
        Pattern headerPattern = Pattern.compile("[\\p{Zs}\\t]*#{1,6}\\s+(.+)");
        Matcher matcher = headerPattern.matcher(text);

        List<SectionBoundary> boundaries = new ArrayList<>();
        while (matcher.find()) {
            String headerName = matcher.group(1).trim();
            int headerStart = matcher.start();
            boundaries.add(new SectionBoundary(headerName, headerStart));
            log.debug("Found section header: '{}' at position {}", headerName, headerStart);
        }

        if (boundaries.isEmpty()) {
            log.warn("No markdown headers found, returning full text as single section");
            return Map.of("FullText", text);
        }

        // Extract text between headers
        for (int i = 0; i < boundaries.size(); i++) {
            SectionBoundary current = boundaries.get(i);

            // Find next header line (skip current header line)
            int contentStart = text.indexOf('\n', current.position);
            if (contentStart == -1) contentStart = current.position;
            else contentStart++; // Skip newline

            int contentEnd = (i < boundaries.size() - 1)
                    ? boundaries.get(i + 1).position
                    : text.length();

            String sectionText = text.substring(contentStart, contentEnd).trim();

            if (!sectionText.isEmpty()) {
                String sectionKey = normalizeSectionName(current.name);
                sections.put(sectionKey, sectionText);
                log.debug("Extracted section '{}': {} chars", sectionKey, sectionText.length());
            }
        }

        if (sections.isEmpty()) {
            log.warn("Section extraction failed, returning full text");
            return Map.of("FullText", text);
        }

        log.info("Extracted {} sections: {}", sections.size(), sections.keySet());
        return sections;
    }

    /**
     * Normalize section names: "Career Objectives" → "CAREER_OBJECTIVES"
     */
    private String normalizeSectionName(String name) {
        return name.toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    /**
     * Helper class to track section boundaries during parsing.
     */
    public static class SectionBoundary {
        final String name;
        final int position;

        SectionBoundary(String name, int position) {
            this.name = name;
            this.position = position;
        }
    }
}
