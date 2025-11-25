package org.example.recruitmentservice.services.metadata.extractors;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DateRangeExtractor {
    /**
     * Pattern for extracting date ranges (work experience periods)
     */
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|" +
                    "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)?" +
                    "\\s*(20\\d{2}|19\\d{2})\\s*[-–—]\\s*" +
                    "(?:(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|" +
                    "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)?" +
                    "\\s*(20\\d{2}|19\\d{2})|present|current)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extracts work experience date ranges from CV text.
     * @return List of date range strings (e.g., "Jan 2020 - Dec 2023")
     */
    public List<String> extractDateRanges(String cvText) {
        if (cvText == null || cvText.isBlank()) {
            return Collections.emptyList();
        }

        List<String> dateRanges = new ArrayList<>();
        Matcher matcher = DATE_RANGE_PATTERN.matcher(cvText);

        while (matcher.find()) {
            dateRanges.add(matcher.group().trim());
        }

        return dateRanges;
    }
}