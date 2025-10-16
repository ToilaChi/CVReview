package org.example.aiservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.CVAnalysisResult;
import org.example.commonlibrary.dto.request.CVAnalysisRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@Component
@RequiredArgsConstructor
public class LlmAnalysisService {
    @Value("${gemini.api-key}")
    private String apiKey;

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent?key=%s";

    public CVAnalysisResult analyze(CVAnalysisRequest req) {
        try {
            String prompt = buildPrompt(req.getJdText(), req.getCvText());

            String response = callGemini(prompt);
            log.info("[LLM] Raw Gemini response: {}", response);

            // Parse Json
            CVAnalysisResult cvAnalysisResult = parseGeminiResponse(response);
            cvAnalysisResult.setCvId(req.getCvId());
            cvAnalysisResult.setBatchId(req.getBatchId());
            cvAnalysisResult.setAnalyzedAt(LocalDateTime.now());

            return cvAnalysisResult;
        } catch (Exception e) {
            log.error("[LLM] Error analyzing CV {}: {}", req.getCvId(), e.getMessage(), e);
            throw new RuntimeException("Gemini analysis failed", e);
        }
    }

    private String buildPrompt(String jd, String cv) {
        return """
            You are an expert HR recruiter and a meticulous technical analyst. Your task is to perform an in-depth,\s
            highly critical, and unbiased analysis of a candidate's CV against the provided Job Description (JD).\s
            This analysis must be universally strict, applying equally to all roles (technical, non-technical, intern, or senior).\s
            Your scoring must be **extremely strict (0-100)**. The criteria for scoring are non-negotiable and must be weighed as follows:
            \s
            1.  **Core Requirement Fit (60%%):** Does the candidate possess the *mandatory* skills (specified in the JD's Qualifications) and relevant experience required for the role? Deduct heavily for missing core frameworks, technologies, or domain-specific knowledge.
            2.  **Quality and Depth of Experience (30%%):** Evaluate the *substance* of the experience, not just the title. Deduct points if projects/experience are basic, simple, lack complexity, or are not directly relevant to the core responsibilities (e.g., using Java Core for a Web Developer JD). High scores are reserved for candidates who demonstrate complex problem-solving, architectural awareness, or measurable impact.
            3.  **Missing Qualifications/Gaps (10%%):** Deduct for missing soft skills, educational requirements, or any significant career gaps/inconsistencies.
            
            **A candidate must score 75+ to be considered for an interview. If not reject.**  
            **Your Output Requirements:**
            1.  **Analyze and Cite:** Explicitly compare each major section of the CV (Summary, Skills, Projects, Education) against the JD's sections (Responsibilities, Qualifications). All findings derived from the documents must be cited.
            2.  **Provide the Final JSON:** Return the final analysis as a valid JSON object ONLY, adhering to the specified format. The 'feedback' must be a concise summary justifying the strict final score.
                
            Compare the following candidate CV with the given Job Description (JD).

            ### Job Description:
            %s

            ### Candidate CV:
            %s

            Return the result as a valid JSON object ONLY in this format:
            {
              "score": 0-100 (integer),
              "feedback": "short feedback summary",
              "skillMatch": ["list", "of", "skills", "present"],
              "skillMiss": ["list", "of", "skills", "missing"]
            }

            Do not add explanations or any text outside the JSON.
           \s""".formatted(jd, cv);
    }

    private String callGemini(String prompt) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();

        String requestBody = """
            {
              "contents": [
                {
                  "parts": [
                    {"text": "%s"}
                  ]
                }
              ]
            }
            """.formatted(escapeJson(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(GEMINI_URL, apiKey)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        if (body.contains("Quota exceeded")) {
            log.warn("[LLM] Quota exceeded, retrying after 10s...");
            Thread.sleep(10000);
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }

        return body;
    }

    private CVAnalysisResult parseGeminiResponse(String rawResponse) {
        try {
            JSONObject json = new JSONObject(rawResponse);
            String text = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text");

            text = extractJson(text);

            JSONObject resultJson = new JSONObject(text);

            CVAnalysisResult result = new CVAnalysisResult();
            result.setScore(resultJson.optDouble("score", 0));
            result.setFeedback(resultJson.optString("feedback", ""));
            result.setSkillMatch(jsonArrayToList(resultJson.optJSONArray("skillMatch")));
            result.setSkillMiss(jsonArrayToList(resultJson.optJSONArray("skillMiss")));
            return result;

        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse Gemini response: " + ex.getMessage(), ex);
        }
    }

    // --- HELPER METHOD ---

    private List<String> jsonArrayToList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        }
        return list;
    }

    private String escapeJson(String s) {
        return s.replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String extractJson(String text) {
        text = text.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "");
        }
        return text.trim();
    }
}
