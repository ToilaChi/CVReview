package org.example.recruitmentservice.client;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.CVUploadEvent;
import org.example.recruitmentservice.models.CVStatus;
import org.example.recruitmentservice.models.CandidateCV;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.services.StorageService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class LlamaParseClient {

    @Value("${llama-parse.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final CandidateCVRepository candidateCVRepository;
    private final StorageService storageService;

    public String parseJD(String filePath) {
        try {
            System.out.println("API Key: " + (apiKey != null ? "exists" : "null"));
            System.out.println("File path: " + filePath);

            // Upload file
            String jobId = uploadFile(filePath);
            System.out.println("Job ID: " + jobId);

            // Poll result
            String parsedText = pollResult(jobId);
            System.out.println("Parse completed!");

            return parsedText;

        } catch (Exception e) {
            System.err.println("Parse failed: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.CV_UPLOAD_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    @Retryable(
            value = {RuntimeException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2, maxDelay = 10000),
            exclude = {IllegalArgumentException.class}
    )
    public void parseCV(CVUploadEvent event) {
        int cvId = event.getCvId();

        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        try {
            cv.setCvStatus(CVStatus.PARSING);
            candidateCVRepository.save(cv);

            String absolutePath = storageService.getAbsolutePath(event.getFilePath());
            File file = new File(absolutePath);

            if (!file.exists()) {
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
            }

            String jobId = uploadFile(absolutePath);
            String parsedText = pollResult(jobId);

            String extractedName = extractName(parsedText);
            String extractedEmail = extractEmail(parsedText);

            cv.setCvContent(parsedText);
            if (extractedName != null) cv.setName(extractedName);
            if (extractedEmail != null) cv.setEmail(extractedEmail);
            cv.setCvStatus(CVStatus.PARSED);
            cv.setParsedAt(LocalDateTime.now());
            cv.setUpdatedAt(LocalDateTime.now());
            candidateCVRepository.save(cv);

            System.out.println("CV parsed successfully - ID: " + cvId +
                    " | Name: " + extractedName + " | Email: " + extractedEmail);
        } catch (Exception e) {
            System.err.println("CV parse failed for cvId " + cvId + ": " + e.getMessage());
            cv.setCvStatus(CVStatus.FAILED);
            cv.setUpdatedAt(LocalDateTime.now());
            candidateCVRepository.save(cv);
        }
    }

    private String uploadFile(String absolutePath) {
        File file = new File(absolutePath);
        if (!file.exists()) {
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
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
                // Check status
                ResponseEntity<Map> statusResponse = restTemplate.exchange(
                        statusUrl,
                        HttpMethod.GET,
                        request,
                        Map.class
                );

                Map<String, Object> statusBody = statusResponse.getBody();
                assert statusBody != null;
                String status = (String) statusBody.get("status");
                System.out.println("Poll #" + (i + 1) + " - Status: " + status);

                if ("SUCCESS".equals(status)) {
                    // Get result
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
                    throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
                }
                // Nếu PENDING hoặc PROCESSING → tiếp tục poll

            } catch (Exception e) {
                System.err.println("Poll error: " + e.getMessage());
                if (e instanceof IllegalArgumentException || e instanceof NullPointerException) {
                    throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
                }
            }

            Thread.sleep(2000);
        }

        throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
    }

    // Regex extract email
    private String extractEmail(String text) {
        Pattern emailPattern = Pattern.compile("[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+");
        Matcher matcher = emailPattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    // Regex extract name
    private String extractName(String text) {
        Pattern namePattern = Pattern.compile("(?i)(?:name|full name)[:\\s]+([A-ZĐ][a-zA-ZĐđ\\s]+)");
        Matcher matcher = namePattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        String[] lines = text.split("\\r?\\n");
        if (lines.length > 0 && lines[0].length() < 50) {
            return lines[0].trim();
        }
        return null;
    }
}