package org.example.recruitmentservice.services.metadata.extractors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.services.chunking.config.ChunkingConfig;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExperienceExtractor {
    /**
     * Pattern for extracting experience years from text.
     * Matches formats like "5 years", "3+ years of experience", etc.
     */
    private static final Pattern EXPERIENCE_PATTERN = Pattern.compile(
            "\\b(\\d+)\\+?\\s*(?:years?|yrs?)(?:\\s+of)?(?:\\s+experience|\\s+exp)?\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extracts years of experience from CV text.
     * Returns the maximum value found.
     */
    public Integer extractExperienceYears(String cvText) {
        if (cvText == null || cvText.isBlank()) {
            return null;
        }

        Matcher matcher = EXPERIENCE_PATTERN.matcher(cvText);
        int maxYears = 0;

        while (matcher.find()) {
            try {
                int years = Integer.parseInt(matcher.group(1));
                maxYears = Math.max(maxYears, years);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse years from: {}", matcher.group(1));
            }
        }

        return maxYears > 0 ? maxYears : null;
    }

    /**
     * Determines seniority level based on years of experience.
     */
    public String determineSeniority(Integer years) {
        if (years == null || years == 0) return "ENTRY";
        if (years <= 2) return "JUNIOR";
        if (years <= 5) return "MID";
        return "SENIOR";
    }
}