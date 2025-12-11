package org.example.recruitmentservice.services.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class MarkdownNormalizer {

    /**
     * Normalize markdown structure to ensure consistency
     * Option 3: ## for main sections, ### for entities, **Bold:** for details
     */
    public String normalize(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }

        log.debug("Normalizing markdown (length: {})", markdown.length());

        String normalized = markdown;

        // Step 1: Remove code fences and artifacts
        normalized = removeCodeFences(normalized);

        // Step 2: Remove name/job title headers at the beginning
        normalized = removeNameHeaders(normalized);

        // Step 3: Normalize headers hierarchy
        normalized = normalizeHeaders(normalized);

        // Step 4: Convert level 4 headers to bold patterns
        normalized = convertLevel4ToBold(normalized);

        // Step 5: Clean up whitespace
        normalized = cleanWhitespace(normalized);

        // Step 6: Fix bullet points
        normalized = normalizeBulletPoints(normalized);

        // Step 7: Merge duplicate sections
        normalized = mergeDuplicateSections(normalized);

        // Step 8: Remove empty sections
        normalized = removeEmptySections(normalized);

        log.debug("Normalized markdown (length: {})", normalized.length());
        return normalized;
    }

    /**
     * Remove code fences (```markdown, ```true```, etc.)
     */
    private String removeCodeFences(String text) {
        String cleaned = text;
        cleaned = cleaned.replaceAll("(?m)^\\s*```(?:markdown|true)?\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*```\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*true\\s*$", "");
        return cleaned;
    }

    /**
     * Remove name and job title headers at the beginning
     * These are typically level 1 headers in the first 200 chars
     */
    private String removeNameHeaders(String text) {
        String[] lines = text.split("\n");
        StringBuilder result = new StringBuilder();

        int processedChars = 0;
        boolean inNameSection = true;

        for (String line : lines) {
            processedChars += line.length();

            // Only process first 300 chars
            if (processedChars > 300) {
                inNameSection = false;
            }

            if (inNameSection && line.trim().matches("^#\\s+[^#\\n]+$")) {
                // This is a level 1 header in name section
                String headerText = line.trim().replaceFirst("^#\\s+", "").trim();

                // Skip if looks like a name or job title (short, no special keywords)
                if (headerText.length() <= 50 &&
                        !isMainSection(headerText) &&
                        headerText.split("\\s+").length <= 6) {
                    log.debug("Removed name/title header: '{}'", headerText);
                    continue;
                }
            }

            result.append(line).append("\n");
        }

        return result.toString();
    }

    /**
     * Normalize all headers to proper levels
     */
    private String normalizeHeaders(String text) {
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n");

        Pattern headerPattern = Pattern.compile("^(#{1,6})\\s+(.+)$");

        for (String line : lines) {
            Matcher matcher = headerPattern.matcher(line.trim());

            if (matcher.matches()) {
                String hashes = matcher.group(1);
                String headerText = matcher.group(2).trim();
                int level = hashes.length();

                String normalized = normalizeHeaderLevel(headerText, level);
                result.append(normalized).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Normalize individual header level
     * - Main sections → ##
     * - Entity names → ###
     * - Entity details → #### (will be converted to bold later)
     */
    private String normalizeHeaderLevel(String headerText, int currentLevel) {
        String lower = headerText.toLowerCase();

        // Clean header text
        String cleanText = headerText
                .replaceAll("^[\\d.\\-)+]+\\s*", "")
                .trim();

        // Capitalize if ALL CAPS
        if (cleanText.equals(cleanText.toUpperCase()) && cleanText.length() > 3) {
            cleanText = capitalizeWords(cleanText);
        }

        // PRIORITY 1: Entity details → #### (temporary, will convert to bold)
        if (isEntityDetail(lower)) {
            return "#### " + cleanText;
        }

        // PRIORITY 2: Main sections → ##
        if (isMainSection(lower)) {
            return "## " + cleanText;
        }

        // PRIORITY 3: Entity names (level 3+) → ###
        if (currentLevel >= 3) {
            return "### " + cleanText;
        }

        // PRIORITY 4: Convert level 1 to level 2
        if (currentLevel == 1) {
            return "## " + cleanText;
        }

        // PRIORITY 5: Keep level 2 as is
        if (currentLevel == 2) {
            return "## " + cleanText;
        }

        return "#".repeat(currentLevel) + " " + cleanText;
    }

    /**
     * Convert level 4 headers to bold patterns
     * #### Duration → **Duration:**
     */
    private String convertLevel4ToBold(String text) {
        Pattern level4Pattern = Pattern.compile("(?m)^####\\s+(.+?)\\s*$");

        Matcher matcher = level4Pattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String headerText = matcher.group(1).trim();

            // Remove trailing colon if exists
            if (headerText.endsWith(":")) {
                headerText = headerText.substring(0, headerText.length() - 1).trim();
            }

            String replacement = "**" + headerText + ":**";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Check if text is a main section
     */
    private boolean isMainSection(String text) {
        String lower = text.toLowerCase();
        return lower.matches(".*(skill|education|experience|work experience|project|certificate|certification|language|objective|summary|contact|about|profile|overview|background|qualification|award|reference|hobby|hobbies|interest|interests).*");
    }

    /**
     * Check if text is an entity detail
     */
    private boolean isEntityDetail(String text) {
        String lower = text.toLowerCase();
        return lower.matches(".*(description|role|responsibility|responsibilities|technology|tech stack|technologies|team size|duration|tools|tool|achievement|achievements|result|results|key responsibilities|key achievements|date|company|position|major|level|gpa|grade).*");
    }

    /**
     * Capitalize words
     */
    private String capitalizeWords(String text) {
        String[] words = text.toLowerCase().split("\\s+");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1))
                        .append(" ");
            }
        }

        return result.toString().trim();
    }

    /**
     * Clean up whitespace
     */
    private String cleanWhitespace(String text) {
        String cleaned = text;
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        cleaned = cleaned.replaceAll("[ \\t]+\n", "\n");
        cleaned = cleaned.replaceAll("[ \\t]+", " ");
        return cleaned.trim();
    }

    /**
     * Normalize bullet points
     */
    private String normalizeBulletPoints(String text) {
        String normalized = text;
        normalized = normalized.replaceAll("(?m)^\\s*[•●○▪▫]\\s+", "- ");
        normalized = normalized.replaceAll("(?m)^\\s*[*]\\s+", "- ");
        normalized = normalized.replaceAll("(?m)^\\s{2,}(- )", "$1");
        return normalized;
    }

    /**
     * Merge duplicate sections (e.g., ## Skills appears twice)
     */
    private String mergeDuplicateSections(String text) {
        Map<String, List<String>> sectionContents = new LinkedHashMap<>();

        Pattern sectionPattern = Pattern.compile(
                "(?m)^##\\s+([^#\\n]+)(?:\\n)([\\s\\S]*?)(?=^##\\s|\\Z)",
                Pattern.MULTILINE
        );

        Matcher matcher = sectionPattern.matcher(text);

        while (matcher.find()) {
            String sectionName = matcher.group(1).trim();
            String content = matcher.group(2).trim();

            sectionContents.computeIfAbsent(sectionName, k -> new ArrayList<>()).add(content);
        }

        // Rebuild text with merged sections
        StringBuilder result = new StringBuilder();

        for (Map.Entry<String, List<String>> entry : sectionContents.entrySet()) {
            String sectionName = entry.getKey();
            List<String> contents = entry.getValue();

            result.append("## ").append(sectionName).append("\n\n");

            // Merge contents
            String mergedContent = contents.stream()
                    .filter(c -> !c.isEmpty())
                    .collect(Collectors.joining("\n\n"));

            result.append(mergedContent).append("\n\n");
        }

        return result.toString();
    }

    /**
     * Remove sections with no content or placeholder content
     */
    private String removeEmptySections(String text) {
        Pattern emptySectionPattern = Pattern.compile(
                "(?m)^##\\s+([^#\\n]+)\\n\\s*(?:\\(No .+ listed\\)|\\(Details not provided\\))?\\s*(?=^##\\s|\\Z)",
                Pattern.MULTILINE
        );

        String cleaned = text;
        Matcher matcher = emptySectionPattern.matcher(cleaned);

        while (matcher.find()) {
            String sectionName = matcher.group(1).trim();
            log.debug("Removing empty section: '{}'", sectionName);
        }

        cleaned = matcher.replaceAll("");

        // Clean up excessive newlines after removal
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");

        return cleaned.trim();
    }
}