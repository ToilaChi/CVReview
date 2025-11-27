package org.example.recruitmentservice.services.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class MarkdownNormalizer {

    /**
     * Normalize markdown structure to ensure consistency
     */
    public String normalize(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }

        log.debug("Normalizing markdown (length: {})", markdown.length());

        String normalized = markdown;

        // Step 1: Remove code fences
        normalized = removeCodeFences(normalized);

        // Step 2: Normalize headers hierarchy
        normalized = normalizeHeaders(normalized);

        // Step 3: Clean up whitespace
        normalized = cleanWhitespace(normalized);

        // Step 4: Fix bullet points
        normalized = normalizeBulletPoints(normalized);

        log.debug("Normalized markdown (length: {})", normalized.length());
        return normalized;
    }

    /**
     * Remove markdown code fences (```markdown, ```true```, etc.)
     */
    private String removeCodeFences(String text) {
        String cleaned = text;

        // Remove opening fences
        cleaned = cleaned.replaceAll("(?m)^\\s*```(?:markdown|true)?\\s*$", "");

        // Remove closing fences
        cleaned = cleaned.replaceAll("(?m)^\\s*```\\s*$", "");

        // Remove inline "true" artifacts
        cleaned = cleaned.replaceAll("(?m)^\\s*true\\s*$", "");

        return cleaned;
    }

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

                // THAY ĐỔI: Logic thông minh hơn
                String normalized = normalizeHeaderLevel(headerText, level);
                result.append(normalized).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    private String normalizeHeaderLevel(String headerText, int currentLevel) {
        String lower = headerText.toLowerCase();

        // Main sections → ## (LUÔN LUÔN)
        if (lower.matches(".*(skill|education|experience|project|certificate|language|objective|summary|contact|about|profile).*")) {
            return "## " + headerText;
        }

        // Entity details (Description, Role...) → ####
        if (lower.matches(".*(description|role|responsibility|responsibilities|technology|tech stack|team size|duration|tools|achievement).*")) {
            return "#### " + headerText;
        }

        // Project/Job names → ###
        if (currentLevel >= 3) {
            return "### " + headerText;
        }

        // THAY ĐỔI: Level 1 đơn lẻ → convert thành ##
        if (currentLevel == 1) {
            return "## " + headerText;
        }

        // Giữ nguyên nếu không rõ
        return "#".repeat(currentLevel) + " " + headerText;
    }

    /**
     * Clean up excessive whitespace
     */
    private String cleanWhitespace(String text) {
        String cleaned = text;

        // Normalize line breaks (max 2 consecutive)
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");

        // Remove trailing spaces
        cleaned = cleaned.replaceAll("[ \\t]+\n", "\n");

        // Normalize spaces
        cleaned = cleaned.replaceAll("[ \\t]+", " ");

        return cleaned.trim();
    }

    /**
     * Normalize bullet points to use consistent "-" prefix
     */
    private String normalizeBulletPoints(String text) {
        String normalized = text;

        // Convert various bullet styles to "-"
        normalized = normalized.replaceAll("(?m)^\\s*[•●○▪▫]\\s+", "- ");
        normalized = normalized.replaceAll("(?m)^\\s*[*]\\s+", "- ");

        // Fix indentation (remove excessive spaces)
        normalized = normalized.replaceAll("(?m)^\\s{2,}(- )", "$1");

        return normalized;
    }
}