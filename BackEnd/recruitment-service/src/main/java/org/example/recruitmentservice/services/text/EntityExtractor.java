package org.example.recruitmentservice.services.text;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts individual entities (projects only) from CV sections.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntityExtractor {

    /**
     * Extract entities from a section - ONLY supports Projects now
     */
    public List<Entity> extractEntities(String sectionName, String sectionText) {
        if (sectionText == null || sectionText.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedSection = sectionName.toUpperCase();

        // Only extract projects
        if (normalizedSection.contains("PROJECT")) {
            return extractProjects(sectionText);
        }

        // Default: return entire section as one entity
        return List.of(Entity.builder()
                .type("SECTION")
                .title(sectionName)
                .content(sectionText)
                .startPos(0)
                .endPos(sectionText.length())
                .build());
    }

    /**
     * Extract individual projects
     * Pattern: ### PROJECT_NAME (YYYY/MM - YYYY/MM) or ### Project Name
     */
    private List<Entity> extractProjects(String text) {
        List<Entity> projects = new ArrayList<>();

        // Match project headers (### or ####) with optional dates
        Pattern projectPattern = Pattern.compile(
                "(?:^|\\n)\\s*#{3,4}\\s+([^#\\n\\r]+?)(?:\\s*\\([0-9/\\-\\s]+\\))?(?=\\s*\\n|$)",
                Pattern.MULTILINE
        );

        Matcher matcher = projectPattern.matcher(text);
        List<ProjectBoundary> boundaries = new ArrayList<>();

        while (matcher.find()) {
            String title = matcher.group(1).trim();

            // Skip if too long (likely false positive)
            if (title.length() > 100) continue;

            boundaries.add(new ProjectBoundary(title, matcher.start()));
        }

        // Extract content between boundaries
        for (int i = 0; i < boundaries.size(); i++) {
            ProjectBoundary current = boundaries.get(i);
            int start = current.position;
            int end = (i < boundaries.size() - 1)
                    ? boundaries.get(i + 1).position
                    : text.length();

            String content = text.substring(start, end).trim();
            if (!content.isEmpty()) {
                projects.add(Entity.builder()
                        .type("PROJECT")
                        .title(current.name)
                        .content(content)
                        .startPos(start)
                        .endPos(end)
                        .build());
            }
        }

        // If no projects found, return whole section
        if (projects.isEmpty()) {
            projects.add(Entity.builder()
                    .type("SECTION")
                    .title("Projects")
                    .content(text)
                    .startPos(0)
                    .endPos(text.length())
                    .build());
        }

        log.debug("Extracted {} projects", projects.size());
        return projects;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class Entity {
        private String type;        // PROJECT, SECTION
        private String title;       // Entity name
        private String content;     // Full text content
        private int startPos;
        private int endPos;
    }

    private static class ProjectBoundary {
        final String name;
        final int position;

        ProjectBoundary(String name, int position) {
            this.name = name;
            this.position = position;
        }
    }
}