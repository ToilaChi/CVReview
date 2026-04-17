package org.example.aiservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.CVAnalysisResult;
import org.example.commonlibrary.dto.request.CVAnalysisRequest;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAnalysisService {
    @Value("${gemini.api-key}")
    private String apiKey;

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=";
    private static final String MODEL = "gemini-2.5-flash";

    /**
     * Singleton Jackson mapper tolerant of unescaped control characters
     * that LLMs may embed in their text output (e.g. literal newlines).
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    // Increased from 2000 to avoid response truncation that causes EOF parse
    // errors.
    private static final int MAX_OUTPUT_TOKENS = 8192;

    public CVAnalysisResult analyze(CVAnalysisRequest req) {
        try {
            String prompt = buildPrompt(req.getJdText(), req.getCvText());
            String rawResponse = callGemini(prompt, req.getCvId());
            log.debug("[LLM-GEMINI] Raw response for cvId={}: {}", req.getCvId(), rawResponse);

            CVAnalysisResult result = parseGeminiResponse(rawResponse);
            result.setCvId(req.getCvId());
            result.setBatchId(req.getBatchId());
            result.setAnalyzedAt(LocalDateTime.now());
            return result;

        } catch (Exception e) {
            log.error("[LLM-GEMINI] Error analyzing CV {}: {}", req.getCvId(), e.getMessage(), e);
            throw new RuntimeException("Gemini analysis failed", e);
        }
    }

    /**
     * Builds the scoring prompt. Instructs the model to output ONLY a raw JSON
     * object with no preamble, reasoning, or markdown to prevent token bloat
     * that would cause the response to be truncated at maxOutputTokens.
     */
    private String buildPrompt(String jd, String cv) {
        return """
                You are a Senior Technical Recruiter with 15 years of experience in Silicon Valley. Your task is to conduct a highly objective, evidence-based screening of a Candidate CV against a Job Description (JD).

                ### SCORING SYSTEM (Total 100 pts)
                1. Core Requirement Fit (Max 60 pts):
                   - Check against "Must-have" skills and technologies.
                   - Deduction: -10 pts for each missing core technology mentioned in JD.
                   - Deduction: -15 pts if the candidate has less than 70% of the required tech stack.

                2. Depth of Experience (Max 30 pts):
                   - Evaluate project complexity, scale (microservices, high traffic), and architectural impact.
                   - 25-30 pts: Expert level, led architectural decisions, measurable business impact.
                   - 15-24 pts: Mid-level, implemented features, understands the 'why' behind tech choices.
                   - 0-14 pts: Junior/Basic projects, CRUD applications only, no evidence of scale or complexity.

                3. Gaps & Professionalism (Max 10 pts):
                   - Soft skills, education, certifications, and career progression.
                   - Deduction: -5 pts for missing degree if required; -5 pts for poor formatting or lack of soft skills evidence.

                ### OPERATIONAL RULES
                - BE STRICT. If a skill is not explicitly stated in the CV, assume the candidate DOES NOT have it.
                - NO HALLUCINATION. Do not infer skills from context unless clearly mentioned.
                - CONSISTENCY: Use the same logic for every evaluation.

                ### WORKFLOW (Internal Reasoning)
                Step 1: List all mandatory skills from JD.
                Step 2: Compare each skill with the CV and mark as "Match" or "Miss".
                Step 3: Calculate deductions for each section based on the criteria above.
                Step 4: Generate a concise summary of the score.

                ### INPUT DATA
                JD: %s
                CV: %s

                ### OUTPUT FORMAT (JSON ONLY)
                Respond with ONLY a raw JSON object. No markdown tags, no preamble.
                {
                  "reasoning_process": "Briefly state the math: e.g., 60(Core)-10(Missing X)-5(Missing Y) + 20(Exp)...",
                  "score": <integer>,
                  "feedback": "<concise paragraph focused on the gap between CV and JD>",
                  "skillMatch": ["skill1", "skill2"],
                  "skillMiss": ["skill1", "skill2"],
                  "decision": "PASS/REJECT" (Threshold 75)
                }"""
                .formatted(jd, cv);
    }

    private String callGemini(String prompt, int cvId) throws IOException, InterruptedException {
        // Note: responseMimeType is intentionally omitted. When set to
        // "application/json",
        // Gemini 2.5-flash can embed unescaped control chars inside the outer JSON
        // envelope
        // causing parse failures. Returning plain text and extracting JSON manually is
        // safer.
        String requestBody = """
                {
                  "contents": [{"parts": [{"text": %s}]}],
                  "generationConfig": {
                    "temperature": 0.0,
                    "maxOutputTokens": %d
                  }
                }
                """.formatted(escapeJsonContent(prompt), MAX_OUTPUT_TOKENS);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(GEMINI_URL, MODEL) + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        if (response.statusCode() == 429 || body.contains("RESOURCE_EXHAUSTED")) {
            log.error("[LLM-GEMINI] Rate limit hit for cvId={}", cvId);
            throw new RuntimeException("Gemini API rate limited - will retry via RabbitMQ");
        }

        if (response.statusCode() != 200) {
            log.error("[LLM-GEMINI] API error for cvId={}: status={}, body={}", cvId, response.statusCode(), body);
            try {
                String errorMessage = MAPPER.readTree(body).path("error").path("message").asText("Unknown error");
                throw new RuntimeException("Gemini API error: " + errorMessage);
            } catch (Exception e) {
                throw new RuntimeException("Gemini API returned HTTP " + response.statusCode());
            }
        }

        return body;
    }

    private CVAnalysisResult parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = MAPPER.readTree(rawResponse);

            JsonNode candidate = root.path("candidates").get(0);
            if (candidate == null || candidate.isMissingNode()) {
                String blockReason = root.path("promptFeedback").path("blockReason").asText("UNKNOWN");
                throw new RuntimeException("No candidates in response. Possible block reason: " + blockReason);
            }

            // Detect truncated output early via finishReason before attempting to parse
            // content.
            String finishReason = candidate.path("finishReason").asText("");
            if ("MAX_TOKENS".equals(finishReason)) {
                throw new RuntimeException(
                        "Gemini response was truncated (finishReason=MAX_TOKENS). " +
                                "The model output exceeded the configured token limit.");
            }

            JsonNode content = candidate.path("content");
            if (content.isMissingNode()) {
                throw new RuntimeException(
                        "No content in candidate (finishReason=" + finishReason +
                                "). Content may have been blocked by safety filters.");
            }

            String text = content.path("parts").get(0).path("text").asText("").trim();
            log.debug("[LLM-GEMINI] Model text output for parsing: {}", text);

            String jsonText = extractJson(text);
            if (jsonText.isEmpty()) {
                throw new RuntimeException("Could not extract a JSON object from model output: " + text);
            }

            JsonNode resultJson = MAPPER.readTree(jsonText);

            List<String> skillMatch = new ArrayList<>();
            resultJson.path("skillMatch").forEach(n -> skillMatch.add(n.asText()));

            List<String> skillMiss = new ArrayList<>();
            resultJson.path("skillMiss").forEach(n -> skillMiss.add(n.asText()));

            CVAnalysisResult result = new CVAnalysisResult();
            result.setScore(resultJson.path("score").asInt(0));
            result.setFeedback(resultJson.path("feedback").asText(""));
            result.setSkillMatch(skillMatch);
            result.setSkillMiss(skillMiss);

            log.info("[LLM-GEMINI] Parsed result: score={}, skillMatch={}, skillMiss={}",
                    result.getScore(), skillMatch.size(), skillMiss.size());

            return result;

        } catch (Exception ex) {
            log.error("[LLM-GEMINI] Failed to parse Gemini response: {}", ex.getMessage());
            log.debug("[LLM-GEMINI] Raw response that failed:\n{}", rawResponse);
            throw new RuntimeException("Failed to parse Gemini response: " + ex.getMessage(), ex);
        }
    }

    private String escapeJsonContent(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            log.error("[LLM-GEMINI] Failed to escape prompt as JSON string", e);
            return "\"\"";
        }
    }

    /**
     * Extracts the first complete JSON object from text that may contain
     * markdown fences, preamble, or trailing explanations.
     * Uses brace-counting instead of lastIndexOf to correctly handle nested
     * objects.
     */
    private String extractJson(String text) {
        text = text.trim();

        // Strip markdown code fences (```json...``` or ```...```)
        if (text.startsWith("```")) {
            text = text.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }

        int start = text.indexOf('{');
        if (start == -1) {
            return "";
        }

        // Walk forward counting braces to find the true matching closing brace
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1).trim();
                }
            }
        }

        return "";
    }
}