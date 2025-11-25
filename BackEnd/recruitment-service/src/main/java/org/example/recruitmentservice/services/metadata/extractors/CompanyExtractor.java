package org.example.recruitmentservice.services.metadata.extractors;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CompanyExtractor {

    /**
     * Pattern for extracting company names
     */
    private static final Pattern COMPANY_PATTERN = Pattern.compile(
            "\\b(?:at|@|worked for|employed by|joined)\\s+([A-Z][\\w\\s&,.-]+?)" +
                    "(?:\\s+(?:as|in|for|,|\\.|$))",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extracts company names from CV text.
     */
    public List<String> extractCompanies(String cvText) {
        if (cvText == null || cvText.isBlank()) {
            return Collections.emptyList();
        }

        Set<String> companies = new LinkedHashSet<>();
        Matcher matcher = COMPANY_PATTERN.matcher(cvText);

        while (matcher.find()) {
            String company = matcher.group(1).trim();
            // Clean up common suffixes
            company = company.replaceAll("(?i)\\s+(Inc|Corp|Ltd|LLC|Company).*$", "");
            if (company.length() > 2) {
                companies.add(company);
            }
        }

        return new ArrayList<>(companies);
    }
}
