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

        Pattern headerPattern = Pattern.compile(
                "(?:^|\\n)\\s*(#{1,3})\\s+([^#\\n\\r]+?)(?=\\s*\\n|$)", // Lấy ##, ###
                Pattern.MULTILINE);

        Matcher matcher = headerPattern.matcher(normalizedText);
        List<SectionBoundary> boundaries = new ArrayList<>();

        while (matcher.find()) {
            String hashes = matcher.group(1);
            String headerName = matcher.group(2).trim();
            int level = hashes.length();
            int headerStart = matcher.start();

            if (headerName.length() > 100)
                continue;

            String normalizedSection = cvSchema.normalizeSection(headerName);

            if (normalizedSection != null) {
                boundaries.add(new SectionBoundary(normalizedSection, headerStart, level));
                log.debug("Found section: '{}' (normalized: '{}') at position {} with level {}",
                        headerName, normalizedSection, headerStart, level);
            } else {
                log.debug("Skipped unknown section: '{}'", headerName);
            }
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
            logHeadersFound(normalizedText); // Debug helper
            return Map.of("FullText", normalizedText);
        }

        // Split entities CHỈ cho PROJECTS và EXPERIENCE
        Map<String, String> finalSections = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sections.entrySet()) {
            String sectionKey = entry.getKey();
            String sectionText = entry.getValue();

            if (cvSchema.isEntitySection(sectionKey)) {
                int parentLevel = 2; // fallback
                for (SectionBoundary curr : boundaries) {
                    if (curr.name.equals(sectionKey)) {
                        parentLevel = curr.level;
                        break;
                    }
                }
                Map<String, String> entities = splitIntoEntities(sectionKey, sectionText, parentLevel);
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

    private Map<String, String> splitIntoEntities(String sectionName, String sectionText, int parentLevel) {
        Map<String, String> entities = new LinkedHashMap<>();

        // Pattern cho headers có level lớn hơn parentLevel (ví dụ: parent là ## thì
        // entity là ###)
        int entityLevel = parentLevel + 1;
        Pattern entityPattern = Pattern.compile(
                "(?:^|\\n)\\s*(#{" + entityLevel + ",})\\s+([^#\\n\\r]+?)(?:\\s*\\([0-9/\\-\\s]+\\))?(?=\\s*\\n|$)",
                Pattern.MULTILINE);

        Matcher matcher = entityPattern.matcher(sectionText);
        List<int[]> entityBoundaries = new ArrayList<>(); // [headerStart, contentStart, nameStart, nameEnd]
        List<String> entityNames = new ArrayList<>();

        while (matcher.find()) {
            String hashes = matcher.group(1);
            String entityName = matcher.group(2).trim();
            if (entityName.length() > 100)
                continue;
            // headerStart: used as end-boundary for previous entity (clean cut before
            // header)
            // contentStart: used as start of this entity's content (after the header line)
            entityBoundaries.add(new int[] { matcher.start(), matcher.end(), hashes.length() });
            entityNames.add(entityName);
        }

        if (entityBoundaries.isEmpty()) {
            entities.put(sectionName, sectionText);
            return entities;
        }

        for (int i = 0; i < entityBoundaries.size(); i++) {
            int[] current = entityBoundaries.get(i);
            int headerStart = current[0];
            int end = (i < entityBoundaries.size() - 1)
                    ? entityBoundaries.get(i + 1)[0] // cut at header start of next entity
                    : sectionText.length();

            // Include header line (from headerStart) + body until end
            String content = sectionText.substring(headerStart, end).trim();
            if (!content.isEmpty()) {
                String entityKey = sectionName + "_" + normalizeEntityName(entityNames.get(i));
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
        if (text == null)
            return "";

        String normalized = text
                .replaceAll("\\p{Zs}", " ")
                .replaceAll("\\u00A0", " ")
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n");

        // Remove stray markdown code fence artifacts (e.g. false```markdown or
        // ```markdown mid-text)
        normalized = normalized.replaceAll("(?i)false```markdown", "");
        normalized = normalized.replaceAll("(?m)^```markdown\\s*$", "");
        normalized = normalized.replaceAll("(?m)^```\\s*$", "");

        // Remove bare "false" tokens left by LlamaParse gpt4o mode
        normalized = normalized.replaceAll("(?m)^\\s*false\\s*$", "");
        normalized = normalized.replaceAll("false(?=#)", ""); // false dính trước header

        // Remove page footer/copyright lines (e.g. "© site.com", "Trang 1/3", "Page 1
        // of 3")
        normalized = normalized.replaceAll("(?m)^.*©.*$", "");
        normalized = normalized.replaceAll("(?m)^\\s*[Tt]rang\\s+\\d+[/\\\\]\\d+.*$", "");
        normalized = normalized.replaceAll("(?m)^\\s*[Pp]age\\s+\\d+\\s+of\\s+\\d+.*$", "");

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