package org.example.recruitmentservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.Map;

@Component
public class LlamaParseClient {

    @Value("${llama-parse.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public String parseFile(String filePath) {
        try {
            System.out.println("🔑 API Key: " + (apiKey != null ? "✅ exists" : "❌ null"));
            System.out.println("📄 File path: " + filePath);

            // Step 1: Upload file
            String jobId = uploadFile(filePath);
            System.out.println("📤 Job ID: " + jobId);

            // Step 2: Poll result
            String parsedText = pollResult(jobId);
            System.out.println("✅ Parse completed!");

            return parsedText;

        } catch (Exception e) {
            System.err.println("❌ Parse failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to parse file", e);
        }
    }

    private String uploadFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + filePath);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + apiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "https://api.cloud.llamaindex.ai/api/parsing/upload",
                request,
                Map.class
        );

        return (String) response.getBody().get("id");
    }

    private String pollResult(String jobId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // Thử các endpoint khác nhau
        String[] endpoints = {
                "https://api.cloud.llamaindex.ai/api/parsing/job/" + jobId + "/result/markdown",
                "https://api.cloud.llamaindex.ai/api/parsing/job/" + jobId + "/result",
                "https://api.cloud.llamaindex.ai/api/parsing/job/" + jobId
        };

        // Poll mỗi 2s, tối đa 60s
        for (int i = 0; i < 30; i++) {
            for (String url : endpoints) {
                try {
                    ResponseEntity<Map> response = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            request,
                            Map.class
                    );

                    Map<String, Object> body = response.getBody();
                    System.out.println("📡 Response from " + url + ": " + body);

                    String status = (String) body.get("status");

                    if ("SUCCESS".equals(status)) {
                        // Thử lấy text từ các field khác nhau
                        if (body.containsKey("markdown")) {
                            return (String) body.get("markdown");
                        } else if (body.containsKey("text")) {
                            return (String) body.get("text");
                        } else if (body.containsKey("result")) {
                            return body.get("result").toString();
                        }
                    } else if ("ERROR".equals(status)) {
                        throw new RuntimeException("Parse job failed");
                    }

                } catch (Exception e) {
                    System.out.println("⚠️ Failed endpoint: " + url + " - " + e.getMessage());
                }
            }

            Thread.sleep(2000);
        }

        throw new RuntimeException("Parse timeout after 60 seconds");
    }
}