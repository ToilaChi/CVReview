package org.example.recruitmentservice.services.metadata.extractors;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EducationExtractor {
    /**
     * Pattern for extracting education degrees
     */
    private static final Pattern DEGREE_PATTERN = Pattern.compile(
            "\\b(Bachelor|Master|PhD|Ph\\.D\\.|B\\.S\\.|M\\.S\\.|B\\.A\\.|M\\.A\\.|MBA)" +
                    "(?:\\s+(?:of|in|degree))?\\s+([\\w\\s]+?)(?:,|\\.|from|at|$)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extracts education degrees and fields from CV text.
     */
    public List<String> extractDegrees(String cvText) {
        if (cvText == null || cvText.isBlank()) {
            return Collections.emptyList();
        }

        Set<String> degrees = new LinkedHashSet<>();
        Matcher matcher = DEGREE_PATTERN.matcher(cvText);

        while (matcher.find()) {
            String degree = matcher.group(1).trim();
            String field = matcher.group(2).trim();
            degrees.add(degree + " in " + field);
        }

        return new ArrayList<>(degrees);
    }
}
