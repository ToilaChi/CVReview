package org.example.recruitmentservice.services.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.services.metadata.model.CVMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiExtractionService {

    @Value("${gemini.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    public CVMetadata extractMetadata(String cvMarkdownText) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API Key is not configured. Falling back to empty metadata.");
            return CVMetadata.empty();
        }

        try {
            String prompt = """
                    You are an HR Data Extractor.
                    Your task is to read the Markdown text of the CV/Resume below and extract the accurate information into a single JSON object.

                    REQUIRE JSON WITH THE FOLLOWING STRUCTURE (do not return Markdown code blocks, only return pure JSON):
                    {
                        "skills": ["Java", "Spring Boot", "AWS"], // list of skills
                        "experienceYears": 3, // Total number of years of work experience (integer, if unknown leave 0)
                        "seniorityLevel": "Mid-level", // Level (Intern, Fresher, Junior, Mid-level, Senior, Lead, Manager)
                        "companies": ["Tech Corp", "ABC JSC"], // Names of companies worked for
                        "degrees": ["Bachelor of IT", "Master of Computer Science"], // Degrees
                        "dateRanges": ["2019-2023", "01/2024-Present"] // Work or study periods found
                    }

                    CV text:
                    ---
                    %s
                    ---
                    """
                    .formatted(cvMarkdownText);

            // Construct Gemini Request Body
            Map<String, Object> requestBody = new HashMap<>();

            Map<String, Object> part = new HashMap<>();
            part.put("text", prompt);

            Map<String, Object> content = new HashMap<>();
            content.put("parts", List.of(part));

            requestBody.put("contents", List.of(content));

            // Force JSON response
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("response_mime_type", "application/json");
            requestBody.put("generationConfig", generationConfig);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            log.info("Calling Gemini API for CV Metadata Extraction...");
            ResponseEntity<String> response = restTemplate.exchange(
                    GEMINI_API_URL + apiKey,
                    HttpMethod.POST,
                    request,
                    String.class);

            JsonNode rootNode = objectMapper.readTree(response.getBody());
            if (rootNode == null || !rootNode.has("candidates")) {
                throw new RuntimeException("Invalid response from Gemini API");
            }

            // Use JsonNode to parse the response safely
            JsonNode firstCandidate = rootNode.path("candidates").get(0);
            JsonNode contentObj = firstCandidate.path("content");
            JsonNode partsArray = contentObj.path("parts");
            String jsonText = partsArray.get(0).path("text").asText();

            // Clean up backticks if model still adds them despite config
            jsonText = jsonText.replace("```json", "").replace("```", "").trim();

            CVMetadata metadata = objectMapper.readValue(jsonText, CVMetadata.class);
            log.info("Successfully extracted CV metadata via Gemini.");
            return metadata;

        } catch (Exception e) {
            log.error("Failed to extract metadata using Gemini API: {}", e.getMessage());
            return CVMetadata.empty();
        }
    }
}
