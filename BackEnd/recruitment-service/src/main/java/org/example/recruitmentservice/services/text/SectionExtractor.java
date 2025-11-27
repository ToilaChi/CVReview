package org.example.recruitmentservice.services.text;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.services.chunking.config.CVSchema;
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
    private final CVSchema cvSchema;

    public Map<String, String> extractSections(String text) {
        String normalizedText = normalizeTextFromLlamaParse(text);
        Map<String, String> sections = new LinkedHashMap<>();

        // Pattern chỉ lấy level 1-2 headers (# hoặc ##)
        Pattern headerPattern = Pattern.compile(
                "(?:^|\\n)\\s*(#{1,2})(?!#)\\s+([^#\\n\\r]+?)(?=\\s*\\n|$)",
                Pattern.MULTILINE
        );

        Matcher matcher = headerPattern.matcher(normalizedText);
        List<SectionBoundary> boundaries = new ArrayList<>();

        while (matcher.find()) {
            String hashes = matcher.group(1);
            String headerName = matcher.group(2).trim();
            int level = hashes.length();
            int headerStart = matcher.start();

            // Skip headers quá dài
            if (headerName.length() > 100) continue;

            // Normalize và validate
            String normalizedSection = cvSchema.normalizeSection(headerName);

            if (normalizedSection == null) {
                log.debug("Skipped unknown section: '{}'", headerName);
                continue;
            }

            boundaries.add(new SectionBoundary(normalizedSection, headerStart, level));
            log.debug("Found section: '{}' (normalized: '{}', level: {}) at position {}",
                    headerName, normalizedSection, level, headerStart);
        }

        if (boundaries.isEmpty()) {
            log.warn("No valid sections found");
            return Map.of("FullText", normalizedText);
        }

        // Skip name header nếu là boundary đầu tiên
        if (boundaries.size() >= 2) {
            SectionBoundary first = boundaries.get(0);
            if (first.position < 50 && first.name.split("\\s+").length <= 4) {
                if (!cvSchema.isValidMainSection(first.name)) {
                    log.info("Skipped name header: '{}'", first.name);
                    boundaries.remove(0);
                }
            }
        }

        // Extract content cho mỗi section
        for (int i = 0; i < boundaries.size(); i++) {
            SectionBoundary current = boundaries.get(i);

            // Tìm end của header line
            String searchText = normalizedText.substring(current.position);
            Matcher headerMatcher = Pattern.compile("^\\s*#{1,2}\\s+[^#\\n\\r]+").matcher(searchText);

            int contentStart = headerMatcher.find()
                    ? current.position + headerMatcher.end()
                    : current.position;

            // Content end = next main section
            int contentEnd = normalizedText.length();
            if (i + 1 < boundaries.size()) {
                contentEnd = boundaries.get(i + 1).position;
            }

            String sectionText = normalizedText.substring(contentStart, contentEnd).trim();

            if (!sectionText.isEmpty()) {
                sections.put(current.name, sectionText);
                log.debug("Extracted section '{}': {} chars", current.name, sectionText.length());
            }
        }

        if (sections.isEmpty()) {
            log.warn("No sections found! Checking raw text...");
            logHeadersFound(normalizedText);  // Debug helper
            return Map.of("FullText", normalizedText);
        }

        // For logging
//        log.info("Extracted {} sections: {}", sections.size(), sections.keySet());
//        sections.forEach((key, value) ->
//                log.debug("Section '{}': {} chars, starts with: '{}'",
//                        key, value.length(), value.substring(0, Math.min(50, value.length())))
//        );

        // Split entities CHỈ cho PROJECTS và EXPERIENCE
        Map<String, String> finalSections = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sections.entrySet()) {
            String sectionKey = entry.getKey();
            String sectionText = entry.getValue();

            if (cvSchema.isEntitySection(sectionKey)) {
                Map<String, String> entities = splitIntoEntities(sectionKey, sectionText);
                finalSections.putAll(entities);
            } else {
                finalSections.put(sectionKey, sectionText);
            }
        }

        log.info("Extracted {} sections: {}", finalSections.size(), finalSections.keySet());
        return finalSections;
    }

    private void logHeadersFound(String text) {
        Pattern allHeaders = Pattern.compile("(?m)^\\s*(#{1,6})\\s+(.+)$");
        Matcher matcher = allHeaders.matcher(text);

        log.debug("=== All headers found in text ===");
        int count = 0;
        while (matcher.find() && count++ < 20) {
            log.debug("Header: '{}' {}", matcher.group(1), matcher.group(2));
        }
        log.debug("=== End headers ===");
    }

    private Map<String, String> splitIntoEntities(String sectionName, String sectionText) {
        Map<String, String> entities = new LinkedHashMap<>();

        // Pattern cho level 3+ headers (###, ####)
        Pattern entityPattern = Pattern.compile(
                "(?:^|\\n)\\s*#{3,}\\s+([^#\\n\\r]+?)(?:\\s*\\([0-9/\\-\\s]+\\))?(?=\\s*\\n|$)",
                Pattern.MULTILINE
        );

        Matcher matcher = entityPattern.matcher(sectionText);
        List<SectionBoundary> entityBoundaries = new ArrayList<>();

        while (matcher.find()) {
            String entityName = matcher.group(1).trim();
            if (entityName.length() > 100) continue;
            entityBoundaries.add(new SectionBoundary(entityName, matcher.start(), 3));
        }

        if (entityBoundaries.isEmpty()) {
            entities.put(sectionName, sectionText);
            return entities;
        }

        for (int i = 0; i < entityBoundaries.size(); i++) {
            SectionBoundary current = entityBoundaries.get(i);
            int start = current.position;
            int end = (i < entityBoundaries.size() - 1)
                    ? entityBoundaries.get(i + 1).position
                    : sectionText.length();

            String content = sectionText.substring(start, end).trim();
            if (!content.isEmpty()) {
                String entityKey = sectionName + "_" + normalizeEntityName(current.name);
                entities.put(entityKey, content);
            }
        }

        return entities;
    }

    private String normalizeEntityName(String name) {
        return name.toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private String normalizeTextFromLlamaParse(String text) {
        if (text == null) return "";

        String normalized = text
                .replaceAll("\\p{Zs}", " ")
                .replaceAll("\\u00A0", " ")
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n");

        normalized = normalized.replaceAll("(\\s)(#{1,6}\\s+)", "$1\n$2");
        normalized = normalized.replaceAll("[ \\t]+", " ");
        normalized = normalized.replaceAll(" *\\n *", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");

        return normalized.trim();
    }

    public static class SectionBoundary {
        final String name;
        final int position;
        final int level;

        SectionBoundary(String name, int position, int level) {
            this.name = name;
            this.position = position;
            this.level = level;
        }
    }
}