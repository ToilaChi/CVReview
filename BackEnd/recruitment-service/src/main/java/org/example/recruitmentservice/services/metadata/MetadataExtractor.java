package org.example.recruitmentservice.services.metadata;

import org.example.recruitmentservice.services.metadata.extractors.*;
import org.example.recruitmentservice.services.metadata.model.CVMetadata;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MetadataExtractor {

    private final SkillsExtractor skillsExtractor;
    private final ExperienceExtractor experienceExtractor;
    private final CompanyExtractor companyExtractor;
    private final EducationExtractor educationExtractor;
    private final DateRangeExtractor dateRangeExtractor;

    /**
     * Extract all metadata from CV text in one call
     */
    public CVMetadata extractAll(String cvText) {
        List<String> skills = skillsExtractor.extractSkills(cvText);
        Integer years = experienceExtractor.extractExperienceYears(cvText);
        String seniority = experienceExtractor.determineSeniority(years);
        List<String> companies = companyExtractor.extractCompanies(cvText);
        List<String> degrees = educationExtractor.extractDegrees(cvText);
        List<String> dateRanges = dateRangeExtractor.extractDateRanges(cvText);

        return CVMetadata.builder()
                .skills(skills)
                .experienceYears(years)
                .seniorityLevel(seniority)
                .companies(companies)
                .degrees(degrees)
                .dateRanges(dateRanges)
                .build();
    }
}
