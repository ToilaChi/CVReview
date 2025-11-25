package org.example.recruitmentservice.services.text;

import lombok.RequiredArgsConstructor;
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
public class SectionExtractor {
    /**
     * Extracts CV sections based on common heading patterns.
     * Returns a map of section names to their content.
     */
    public Map<String, String> extractSections(String text) {
        Map<String, String> sections = new LinkedHashMap<>();

        // Regex để match markdown headers: # Summary, ## Education, etc.
        Pattern headerPattern = Pattern.compile("^\\s*#{1,6}\\s+(.+?)\\s*$", Pattern.MULTILINE);
        Matcher matcher = headerPattern.matcher(text);

        List<SectionBoundary> boundaries = new ArrayList<>();
        while (matcher.find()) {
            boundaries.add(new SectionBoundary(
                    matcher.group(1).trim(),
                    matcher.start()
            ));
        }

        // Extract text giữa các headers
        for (int i = 0; i < boundaries.size(); i++) {
            SectionBoundary current = boundaries.get(i);
            int contentStart = text.indexOf('\n', current.position);
            if (contentStart == -1) contentStart = current.position;
            else contentStart++; // Skip newline

            int contentEnd = (i < boundaries.size() - 1)
                    ? boundaries.get(i + 1).position
                    : text.length();

            String sectionText = text.substring(contentStart, contentEnd).trim();
            String sectionKey = normalizeSectionName(current.name);
            sections.put(sectionKey, sectionText);
        }

        return sections.isEmpty()
                ? Map.of("FullText", text)
                : sections;
    }

    // Normalize section names: "Career Objectives" → "CAREER_OBJECTIVES"
    private String normalizeSectionName(String name) {
        return name.toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    /**
     * Helper class to track section boundaries during parsing.
     */
    public static class SectionBoundary {
        final int position;
        final String name;

        SectionBoundary(String name, int position) {
            this.name = name;
            this.position = position;
        }
    }
}
