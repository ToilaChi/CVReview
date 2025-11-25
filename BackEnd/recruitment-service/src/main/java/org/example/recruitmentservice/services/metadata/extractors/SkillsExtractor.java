package org.example.recruitmentservice.services.metadata.extractors;

import lombok.RequiredArgsConstructor;
import org.example.recruitmentservice.services.chunking.config.ChunkingConfig;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class SkillsExtractor {
    private final ChunkingConfig config;

    /**
     * Extracts technical skills from CV text using word boundary matching.
     * More accurate than simple contains() check.
     */
    public List<String> extractSkills(String cvText) {
        if (cvText == null || cvText.isBlank()) {
            return Collections.emptyList();
        }

        Set<String> foundSkills = new LinkedHashSet<>();
        String lowerText = cvText.toLowerCase();

        for (String skill : config.getTechKeywords()) {
            // Use word boundary for more accurate matching
            String pattern = "\\b" + Pattern.quote(skill.toLowerCase()) + "\\b";
            if (Pattern.compile(pattern).matcher(lowerText).find()) {
                foundSkills.add(skill);
            }
        }

        return new ArrayList<>(foundSkills);
    }
}
