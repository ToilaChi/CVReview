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
 * Extracts individual entities (projects, jobs, education) from CV sections.
 * Each entity represents a logical unit for chunking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntityExtractor {

    /**
     * Extract entities from a section (projects, jobs, education entries)
     */
    public List<Entity> extractEntities(String sectionName, String sectionText) {
        if (sectionText == null || sectionText.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedSection = sectionName.toUpperCase();

        // Try entity extraction based on section type
        if (normalizedSection.contains("PROJECT")) {
            return extractProjects(sectionText);
        } else if (normalizedSection.contains("EXPERIENCE") || normalizedSection.contains("WORK")) {
            return extractJobs(sectionText);
        } else if (normalizedSection.contains("EDUCATION")) {
            return extractEducation(sectionText);
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
     * Pattern: # PROJECT_NAME (YYYY/MM - YYYY/MM) or ### Project Name
     */
    private List<Entity> extractProjects(String text) {
        List<Entity> projects = new ArrayList<>();

        // Match project headers with dates or markdown headers
        Pattern projectPattern = Pattern.compile(
                "(?:^|\\n)\\s*(?:" +
                        "#{1,4}\\s+([^\\n]+)|" +                    // ### Project Name
                        "([A-Z][A-Z\\s]+)\\s*\\([0-9/\\-]+\\)|" +  // PROJECT NAME (2024/01 - 2024/12)
                        "\\*\\*([^*]+)\\*\\*\\s*\\([0-9/\\-]+\\)" +    // **Project** (2024/01)
                        ")",
                Pattern.MULTILINE
        );

        Matcher matcher = projectPattern.matcher(text);
        List<ProjectBoundary> boundaries = new ArrayList<>();

        while (matcher.find()) {
            String title = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3);
            if (title != null) {
                boundaries.add(new ProjectBoundary(title.trim(), matcher.start()));
            }
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

    /**
     * Extract individual job experiences
     * Pattern: Position at Company (YYYY - YYYY)
     */
    private List<Entity> extractJobs(String text) {
        List<Entity> jobs = new ArrayList<>();

        // Match job titles with company and dates
        Pattern jobPattern = Pattern.compile(
                "(?:^|\\n)\\s*(?:" +
                        "([A-Z][a-zA-Z\\s]+?)\\s+(?:at|@|,)\\s+([A-Z][^\\n]+?)\\s*\\(([0-9\\-\\s/]+)\\)|" +
                        "#{1,4}\\s+([^\\n]+)" +
                        ")",
                Pattern.MULTILINE
        );

        Matcher matcher = jobPattern.matcher(text);
        List<ProjectBoundary> boundaries = new ArrayList<>();

        while (matcher.find()) {
            String title = matcher.group(1) != null
                    ? matcher.group(1) + " at " + matcher.group(2)
                    : matcher.group(4);
            if (title != null) {
                boundaries.add(new ProjectBoundary(title.trim(), matcher.start()));
            }
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
                jobs.add(Entity.builder()
                        .type("JOB")
                        .title(current.name)
                        .content(content)
                        .startPos(start)
                        .endPos(end)
                        .build());
            }
        }

        // If no jobs found, return whole section
        if (jobs.isEmpty()) {
            jobs.add(Entity.builder()
                    .type("SECTION")
                    .title("Experience")
                    .content(text)
                    .startPos(0)
                    .endPos(text.length())
                    .build());
        }

        log.debug("Extracted {} jobs", jobs.size());
        return jobs;
    }

    /**
     * Extract education entries
     */
    private List<Entity> extractEducation(String text) {
        List<Entity> entries = new ArrayList<>();

        // Match education entries with dates
        Pattern eduPattern = Pattern.compile(
                "(?:^|\\n)\\s*(?:" +
                        "([0-9/\\-]+)\\s+(?:-|–)\\s+([A-Za-z\\s]+)|" +
                        "#{1,4}\\s+([^\\n]+)" +
                        ")",
                Pattern.MULTILINE
        );

        Matcher matcher = eduPattern.matcher(text);
        List<ProjectBoundary> boundaries = new ArrayList<>();

        while (matcher.find()) {
            String title = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (title != null) {
                boundaries.add(new ProjectBoundary(title.trim(), matcher.start()));
            }
        }

        // Extract content
        for (int i = 0; i < boundaries.size(); i++) {
            ProjectBoundary current = boundaries.get(i);
            int start = current.position;
            int end = (i < boundaries.size() - 1)
                    ? boundaries.get(i + 1).position
                    : text.length();

            String content = text.substring(start, end).trim();
            if (!content.isEmpty()) {
                entries.add(Entity.builder()
                        .type("EDUCATION")
                        .title(current.name)
                        .content(content)
                        .startPos(start)
                        .endPos(end)
                        .build());
            }
        }

        // If no entries found, return whole section
        if (entries.isEmpty()) {
            entries.add(Entity.builder()
                    .type("SECTION")
                    .title("Education")
                    .content(text)
                    .startPos(0)
                    .endPos(text.length())
                    .build());
        }

        log.debug("Extracted {} education entries", entries.size());
        return entries;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class Entity {
        private String type;        // PROJECT, JOB, EDUCATION, SECTION
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
