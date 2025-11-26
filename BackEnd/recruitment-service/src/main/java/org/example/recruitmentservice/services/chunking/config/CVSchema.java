package org.example.recruitmentservice.services.chunking.config;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class CVSchema {

    private static final List<String> MAIN_SECTIONS = List.of(
            "CONTACT",
            "SUMMARY",
            "SKILLS",
            "EDUCATION",
            "EXPERIENCE",
            "PROJECTS",
            "CERTIFICATES",
            "LANGUAGES",
            "CAREER_OBJECTIVE",
            "ACTIVITIES",
            "HOBBIES"
    );

    // Sections có thể chứa sub-entities (level 3+)
    private static final Set<String> ENTITY_SECTIONS = Set.of(
            "PROJECTS",
            "EXPERIENCE"
    );

    // Mapping patterns -> standard section names
    private static final Map<Pattern, String> SECTION_MAPPING;

    static {
        Map<Pattern, String> map = new LinkedHashMap<>();
        map.put(Pattern.compile(".*(contact|information|detail).*", Pattern.CASE_INSENSITIVE), "CONTACT");
        map.put(Pattern.compile(".*(summary|profile|about|overview).*", Pattern.CASE_INSENSITIVE), "SUMMARY");
        map.put(Pattern.compile(".*(skill|technical|competenc).*", Pattern.CASE_INSENSITIVE), "SKILLS");
        map.put(Pattern.compile(".*(education|academic|university).*", Pattern.CASE_INSENSITIVE), "EDUCATION");
        map.put(Pattern.compile(".*(experience|employment|work history).*", Pattern.CASE_INSENSITIVE), "EXPERIENCE");
        map.put(Pattern.compile(".*(project|portfolio).*", Pattern.CASE_INSENSITIVE), "PROJECTS");
        map.put(Pattern.compile(".*(certificate|award|achievement).*", Pattern.CASE_INSENSITIVE), "CERTIFICATES");
        map.put(Pattern.compile(".*(language|fluenc).*", Pattern.CASE_INSENSITIVE), "LANGUAGES");
        map.put(Pattern.compile(".*(objective|goal|career).*", Pattern.CASE_INSENSITIVE), "CAREER_OBJECTIVE");
        map.put(Pattern.compile(".*(extracurricular|volunteer|community|activit).*", Pattern.CASE_INSENSITIVE), "ACTIVITIES");
        map.put(Pattern.compile(".*(hobby|hobbies|interest).*", Pattern.CASE_INSENSITIVE), "HOBBIES");
        SECTION_MAPPING = Collections.unmodifiableMap(map);
    }

    public List<String> getMainSections() {
        return MAIN_SECTIONS;
    }

    public boolean isEntitySection(String section) {
        return ENTITY_SECTIONS.contains(section);
    }

    public String normalizeSection(String rawName) {
        if (rawName == null || rawName.isBlank()) return null;

        String cleaned = rawName.trim();

        // Try pattern matching
        for (Map.Entry<Pattern, String> entry : SECTION_MAPPING.entrySet()) {
            if (entry.getKey().matcher(cleaned).matches()) {
                return entry.getValue();
            }
        }

        return null; // Unknown section
    }

    public boolean isValidMainSection(String section) {
        return MAIN_SECTIONS.contains(section);
    }
}
