package org.example.aiservice.services;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.CVAnalysisResult;
import org.example.commonlibrary.dto.request.CVAnalysisRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LlmAnalysisService {
    public CVAnalysisResult analyze(CVAnalysisRequest req) {
        // --- Simple mock logic for now (replace with real LLM call) ---
        double score = simpleScore(req);
        List<String> matches = List.of("Java", "Spring Boot"); // mock
        List<String> misses = List.of("Kafka"); // mock

        CVAnalysisResult r = new CVAnalysisResult();
        r.setCvId(req.getCvId());
        r.setScore(score);
        r.setFeedback("Mock feedback - replace with LLM output");
        r.setSkillMatch(matches);
        r.setSkillMiss(misses);
        r.setBatchId(req.getBatchId());
        r.setAnalyzedAt(java.time.LocalDateTime.now());
        return r;
    }

    private double simpleScore(CVAnalysisRequest req) {
        String cv = req.getCvText().toLowerCase();
        String jd = req.getJdText().toLowerCase();
        int hits = 0;
        if (jd.contains("java") && cv.contains("java")) hits++;
        if (jd.contains("spring") && cv.contains("spring")) hits++;
        if (jd.contains("sql") && cv.contains("sql")) hits++;
        return Math.min(100.0, hits * 33.0);
    }
}
