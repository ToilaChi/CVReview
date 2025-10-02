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
            System.out.println("API Key: " + (apiKey != null ? "exists" : "null"));
            System.out.println("File path: " + filePath);

            // Step 1: Upload file
            String jobId = uploadFile(filePath);
            System.out.println("Job ID: " + jobId);

            // Step 2: Poll result
            String parsedText = pollResult(jobId);
            System.out.println("Parse completed!");

            return parsedText;

        } catch (Exception e) {
            System.err.println("Parse failed: " + e.getMessage());
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

        String statusUrl = "https://api.cloud.llamaindex.ai/api/parsing/job/" + jobId;
        String resultUrl = "https://api.cloud.llamaindex.ai/api/parsing/job/" + jobId + "/result/markdown";

        // Poll mỗi 2s, tối đa 60s
        for (int i = 0; i < 30; i++) {
            try {
                // Bước 1: Check status
                ResponseEntity<Map> statusResponse = restTemplate.exchange(
                        statusUrl,
                        HttpMethod.GET,
                        request,
                        Map.class
                );

                Map<String, Object> statusBody = statusResponse.getBody();
                String status = (String) statusBody.get("status");
                System.out.println("📡 Poll #" + (i + 1) + " - Status: " + status);

                if ("SUCCESS".equals(status)) {
                    // Bước 2: Lấy kết quả
                    ResponseEntity<Map> resultResponse = restTemplate.exchange(
                            resultUrl,
                            HttpMethod.GET,
                            request,
                            Map.class
                    );

                    Map<String, Object> resultBody = resultResponse.getBody();
                    if (resultBody.containsKey("markdown")) {
                        String markdown = (String) resultBody.get("markdown");
                        System.out.println("Parse completed! Text length: " + markdown.length());
                        return markdown;
                    }
                } else if ("ERROR".equals(status)) {
                    throw new RuntimeException("Parse job failed with status ERROR");
                }
                // Nếu PENDING hoặc PROCESSING → tiếp tục poll

            } catch (Exception e) {
                System.err.println("⚠️ Poll error: " + e.getMessage());
            }

            Thread.sleep(2000);
        }

        throw new RuntimeException("Parse timeout after 60 seconds");
    }
}