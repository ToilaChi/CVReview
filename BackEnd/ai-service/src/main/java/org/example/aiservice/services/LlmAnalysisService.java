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
    @Value("${groq.api-key}")
    private String apiKey;

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    // Các model Groq có thể dùng:
    // - llama-3.3-70b-versatile (mới nhất, khuyến nghị)
    // - llama-3.1-70b-versatile
    // - llama-3.1-8b-instant (nhanh nhất)
    // - mixtral-8x7b-32768
    private static final String MODEL = "llama-3.3-70b-versatile";

    public CVAnalysisResult analyze(CVAnalysisRequest req) {
        try {
            String prompt = buildPrompt(req.getJdText(), req.getCvText());

            String response = callGroq(prompt, req.getCvId());
            log.info("[LLM-GROQ] Raw Groq response: {}", response);

            // Parse JSON
            CVAnalysisResult cvAnalysisResult = parseGroqResponse(response);
            cvAnalysisResult.setCvId(req.getCvId());
            cvAnalysisResult.setBatchId(req.getBatchId());
            cvAnalysisResult.setAnalyzedAt(LocalDateTime.now());

            return cvAnalysisResult;
        } catch (Exception e) {
            log.error("[LLM-GROQ] Error analyzing CV {}: {}", req.getCvId(), e.getMessage(), e);
            throw new RuntimeException("Groq analysis failed", e);
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

    private String callGroq(String prompt, int cvId) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();

        // Groq sử dụng format OpenAI-compatible
        String requestBody = """
            {
              "model": "%s",
              "messages": [
                {
                  "role": "user",
                  "content": %s
                }
              ],
              "temperature": 0.3,
              "max_tokens": 2000,
              "response_format": {"type": "json_object"}
            }
            """.formatted(MODEL, escapeJsonContent(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        // Check for API errors
        if (response.statusCode() != 200) {
            log.error("[LLM-GROQ] Groq API error for cvId={}: status={}, body={}",
                    cvId, response.statusCode(), body);

            // Parse error message
            try {
                JSONObject errorJson = new JSONObject(body);
                String errorMessage = errorJson.getJSONObject("error").getString("message");
                throw new RuntimeException("Groq API error: " + errorMessage);
            } catch (Exception e) {
                throw new RuntimeException("Groq API returned status " + response.statusCode());
            }
        }

        // Check for rate limit (Groq trả về 429)
        if (body.contains("rate_limit_exceeded") || body.contains("Rate limit")) {
            log.error("[LLM-GROQ] Rate limit hit for cvId={}", cvId);
            throw new RuntimeException("Groq API rate limited - will retry via RabbitMQ");
        }

        return body;
    }

    private CVAnalysisResult parseGroqResponse(String rawResponse) {
        try {
            JSONObject json = new JSONObject(rawResponse);

            // Groq format: response.choices[0].message.content
            String text = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

            // Groq với response_format: json_object sẽ trả về pure JSON
            // nhưng vẫn cần extract để chắc chắn
            text = extractJson(text);

            JSONObject resultJson = new JSONObject(text);

            CVAnalysisResult result = new CVAnalysisResult();
            result.setScore(resultJson.optInt("score", 0));
            result.setFeedback(resultJson.optString("feedback", ""));
            result.setSkillMatch(jsonArrayToList(resultJson.optJSONArray("skillMatch")));
            result.setSkillMiss(jsonArrayToList(resultJson.optJSONArray("skillMiss")));

            log.info("[LLM-GROQ] Parsed result: score={}, skillMatch={}, skillMiss={}",
                    result.getScore(), result.getSkillMatch().size(), result.getSkillMiss().size());

            return result;

        } catch (Exception ex) {
            log.error("[LLM-GROQ] Failed to parse Groq response: {}", ex.getMessage());
            throw new RuntimeException("Failed to parse Groq response: " + ex.getMessage(), ex);
        }
    }

    // --- HELPER METHODS ---

    private List<String> jsonArrayToList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        }
        return list;
    }

    private String escapeJsonContent(String s) {
        // Escape cho JSON string value
        return new JSONObject()
                .put("temp", s)
                .toString()
                .replaceAll("^\\{\"temp\":", "")
                .replaceAll("\\}$", "");
    }

    private String extractJson(String text) {
        text = text.trim();

        // Remove markdown code blocks nếu có
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "");
        }

        // Tìm JSON object đầu tiên
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');

        if (start != -1 && end != -1 && end > start) {
            text = text.substring(start, end + 1);
        }

        return text.trim();
    }
}