package org.example.recruitmentservice.client;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.CVChunkedEvent;
import org.example.recruitmentservice.dto.request.CVUploadEvent;
import org.example.recruitmentservice.dto.request.ChunkPayload;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.services.ProcessingBatchService;
import org.example.recruitmentservice.services.StorageService;
import org.example.recruitmentservice.services.chunking.ChunkingService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
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
    private final ProcessingBatchService processingBatchService;
    private final ChunkingService chunkingService;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Parse JD từ file path (temp file đã download từ Drive)
     */
    public String parseJD(String filePath) {
        try {
            System.out.println("API Key: " + (apiKey != null ? "exists" : "null"));
            System.out.println("File path: " + filePath);

            String jobId = uploadFileForJD(filePath);
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

    /**
     * RabbitMQ Listener - Parse CV từ Drive
     */
    @RabbitListener(queues = RabbitMQConfig.CV_UPLOAD_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    @Transactional
    public void parseCV(CVUploadEvent event) {
        int cvId = event.getCvId();

        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        String tempFilePath = null;
        try {
            cv.setCvStatus(CVStatus.PARSING);
            candidateCVRepository.save(cv);

            // Download file từ Drive về temp
            String fileId = event.getFileId();
            tempFilePath = storageService.downloadFileToTemp(fileId);

            File file = new File(tempFilePath);
            if (!file.exists()) {
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
            }

            String jobId = uploadFileForCV(tempFilePath);
            String parsedText = pollResult(jobId);

            // Extract information
            String extractedName = extractName(parsedText);
            String extractedEmail = extractEmail(parsedText);

            // Chunking
            System.out.println("Starting chunking for CV: " + cvId);
            List<ChunkPayload> chunks = chunkingService.chunk(cv, parsedText);

            if (chunks == null || chunks.isEmpty()) {
                System.err.println("Warning: No chunks generated for CV " + cvId);
                throw new CustomException(ErrorCode.CV_CHUNKING_FAILED);
            }

            System.out.println("Generated " + chunks.size() + " chunks for CV: " + cvId);

            // Update CV entity
            cv.setCvContent(parsedText);
            if (extractedName != null) cv.setName(extractedName);
            if (extractedEmail != null) cv.setEmail(extractedEmail);
            cv.setCvStatus(CVStatus.PARSED);
            cv.setParsedAt(LocalDateTime.now());
            cv.setUpdatedAt(LocalDateTime.now());
            candidateCVRepository.save(cv);

            // Update batch
            processingBatchService.incrementProcessed(event.getBatchId(), true);

            System.out.println("CV parsed successfully - ID: " + cvId +
                    " | Name: " + extractedName +
                    " | Email: " + extractedEmail +
                    " | Source: " + cv.getSourceType() +
                    " | Position: " + (cv.getPosition() != null ? cv.getPosition().getId() : "N/A"));

            publishCVChunkedEvent(cv, chunks);

        } catch (Exception e) {
            System.err.println("CV parse failed for cvId " + cvId + ": " + e.getMessage());
            cv.setCvStatus(CVStatus.FAILED);
            cv.setErrorMessage(e.getMessage());
            cv.setUpdatedAt(LocalDateTime.now());
            candidateCVRepository.save(cv);

            // Update batch with failure
            processingBatchService.incrementProcessed(event.getBatchId(), false);

            throw new CustomException(ErrorCode.CV_PARSE_FAILED);
        } finally {
            // Cleanup temp file
            if (tempFilePath != null) {
                storageService.deleteTempFile(tempFilePath);
            }
        }
    }

    // HELPER METHODS

    /**
     * Upload file cho JD - Config đơn giản, chỉ cần parse đầy đủ text và giữ structure
     */
    private String uploadFileForJD(String absolutePath) {
        File file = new File(absolutePath);
        if (!file.exists()) {
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + apiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));
        body.add("parsing_instruction",
                "Extract all text from this Job Description document. " +
                        "Preserve the original structure and formatting. " +
                        "Include all sections, requirements, and details.");
        body.add("result_type", "markdown");
        body.add("invalidate_cache", "true");

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "https://api.cloud.llamaindex.ai/api/parsing/upload",
                request,
                Map.class
        );

        return (String) response.getBody().get("id");
    }

    /**
     * Upload file cho CV - Config chi tiết với parsing instruction đầy đủ
     */
    private String uploadFileForCV(String absolutePath) {
        File file = new File(absolutePath);
        if (!file.exists()) {
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + apiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));
        body.add("parsing_instruction",
                "Extract text from this CV/Resume and structure it in markdown format following these STRICT rules:\n\n" +

                        "HEADER HIERARCHY (MUST FOLLOW):\n" +
                        "- Use ## (level 2) ONLY for main sections: Contact, Summary, Skills, Education, Experience, Projects, Certificates, Languages, Awards, Objective\n" +
                        "- Use ### (level 3) ONLY for entity names: project names, company names, job titles, school names, degree names, award names\n" +
                        "- DO NOT use #### (level 4) headers at all\n" +
                        "- DO NOT use # (level 1) headers at all\n" +
                        "- For entity details (Duration, Description, Role, etc.), use **Bold:** format, NOT headers\n\n" +

                        "EXAMPLES:\n\n" +

                        "## Contact\n" +
                        "- Name: John Doe\n" +
                        "- Email: john@example.com\n" +
                        "- Phone: +84 123456789\n\n" +

                        "## Skills\n" +
                        "- Java, Python, React, Node.js\n" +
                        "- MySQL, PostgreSQL, MongoDB\n" +
                        "- Docker, Kubernetes, AWS\n\n" +

                        "## Education\n\n" +
                        "### Bachelor of Computer Science\n" +
                        "**School:** ABC University\n" +
                        "**Duration:** 2015/09 - 2019/06\n" +
                        "**GPA:** 3.5/4.0\n\n" +

                        "## Experience\n\n" +
                        "### Software Engineer\n" +
                        "**Company:** Tech Corp JSC\n" +
                        "**Duration:** 2020/01 - Present\n" +
                        "**Responsibilities:**\n" +
                        "- Developed backend APIs using Spring Boot\n" +
                        "- Led team of 3 developers\n" +
                        "- Improved system performance by 40%\n\n" +

                        "## Projects\n\n" +

                        "### E-commerce Platform\n" +
                        "**Duration:** 2021/06 - 2022/12\n" +
                        "**Description:** Built a full-stack e-commerce platform with payment integration\n" +
                        "**Role:** Full-stack Developer\n" +
                        "**Technologies:**\n" +
                        "- Frontend: React, Redux, Tailwind CSS\n" +
                        "- Backend: Spring Boot, PostgreSQL, Redis\n" +
                        "- Infrastructure: Docker, AWS EC2, S3\n" +
                        "**Responsibilities:**\n" +
                        "- Designed RESTful APIs\n" +
                        "- Implemented payment gateway\n" +
                        "- Deployed using Docker\n\n" +

                        "## Awards\n\n" +
                        "### Best Innovation Award 2022\n" +
                        "**Date:** 2022/08\n" +
                        "**Result:** 1st place\n" +
                        "**Description:** Won first place in company hackathon\n\n" +

                        "IMPORTANT FORMATTING RULES:\n" +
                        "- Main sections (##) should have NO content immediately after, add blank line\n" +
                        "- Entity names (###) should be followed by entity details in **Bold:** format\n" +
                        "- Use **Duration:**, **Description:**, **Role:**, **Technologies:**, **Responsibilities:**, **Company:**, **School:**, **Date:**, etc.\n" +
                        "- Use '-' for bullet points (not *, •, or numbers)\n" +
                        "- Keep consistent spacing: blank line after each entity\n" +
                        "- Extract ALL text content from the CV\n" +
                        "- Do NOT wrap output in code blocks or backticks\n" +
                        "- Do NOT add any preamble or explanation\n" +
                        "- Do NOT use person's name or job title as headers"
        );
        body.add("result_type", "markdown");
        body.add("target_pages", "");
        body.add("invalidate_cache", "true");
        body.add("gpt4o_mode", "false");
        body.add("skip_diagonal_text", "true");
        body.add("extract_all_pages", "true");
        body.add("do_not_unroll_columns", "false");
        body.add("page_separator", "false");
        body.add("prefix_or_suffix", "false");
        body.add("continuous_mode", "true");
        body.add("fast_mode", "false");

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

    private void publishCVChunkedEvent(CandidateCV cv, List<ChunkPayload> chunks) {
        // Calculate total tokens
        int totalTokens = chunks.stream()
                .mapToInt(ChunkPayload::getTokensEstimate)
                .sum();

        // Prepare event
        CVChunkedEvent event = new CVChunkedEvent(
                cv.getId(),
                cv.getCandidateId(),
                cv.getHrId(),
                cv.getPosition() != null ? cv.getPosition().getName() : null,
                chunks,
                chunks.size(),
                totalTokens
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                rabbitTemplate.convertAndSend(
                                        RabbitMQConfig.CV_CHUNKED_EXCHANGE,
                                        RabbitMQConfig.CV_CHUNKED_ROUTING_KEY,
                                        event
                                );
                                System.out.println("Published CV chunked event for CV: " + cv.getId() +
                                        " with " + chunks.size() + " chunks");
                            } catch (Exception e) {
                                System.err.println("Failed to publish CV chunked event: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
            );
        } else {
            // Fallback if no transaction (shouldn't happen with @Transactional)
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.CV_CHUNKED_EXCHANGE,
                        RabbitMQConfig.CV_CHUNKED_ROUTING_KEY,
                        event
                );
                System.out.println("Published CV chunked event (no tx) for CV: " + cv.getId());
            } catch (Exception e) {
                System.err.println("Failed to publish CV chunked event (no tx): " + e.getMessage());
            }
        }
    }

    // Regex extract email
    private String extractEmail(String text) {
        if (text == null || text.isEmpty()) return null;

        Pattern emailPattern = Pattern.compile(
                "(?i)(?:email|mail)[:\\s]*([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})"
        );
        Matcher matcher = emailPattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // Fallback – bắt mọi email trong text (phòng khi không có label "Email:")
        matcher = Pattern.compile("([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})").matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    // Regex extract name
    private String extractName(String text) {
        if (text == null || text.isEmpty()) return null;

        // Loại bỏ ký tự đặc biệt cơ bản trong text
        String cleanedText = text.replaceAll("[#%@\\-!$^&*()_+=\\[\\]{}|\\\\;:\"'<>,/?~`]", " ");

        // Regex chính – tìm các dạng "Name:", "Full Name:", "Họ tên:", "Fullname:"
        Pattern namePattern = Pattern.compile(
                "(?i)(?:^|\\b)(?:name|full name|họ tên)[:\\s]+([A-ZĐ][a-zA-ZĐđ\\s]+?)(?=\\b(?:date|dob|birth|email|phone|address|\\r?\\n|$))"
        );
        Matcher matcher = namePattern.matcher(cleanedText);
        if (matcher.find()) {
            String name = matcher.group(1).trim();

            // Loại bỏ các phần thừa (nếu có)
            name = name.replaceAll("(?i)\\b(date of birth|dob|email|phone|address).*", "").trim();

            // Loại bỏ các ký tự không phải chữ và space
            name = name.replaceAll("[^a-zA-ZĐđ\\s]", "").trim();

            // Giới hạn độ dài hợp lý
            if (name.length() > 50) name = name.substring(0, 50).trim();
            return name;
        }

        // Fallback #1: Dòng đầu tiên
        String[] lines = cleanedText.split("\\r?\\n");
        if (lines.length > 0) {
            String firstLine = lines[0].trim();
            if (firstLine.length() <= 40 && !firstLine.matches(".*[@0-9,.:/].*")) {
                // Chỉ giữ chữ và space
                return firstLine.replaceAll("[^a-zA-ZĐđ\\s]", "").trim();
            }
        }

        // Fallback #2: Dòng nào đó có dạng chữ
        Matcher fallbackMatcher = Pattern.compile("\\b([A-ZĐ][a-zA-ZĐđ]+\\s+[A-ZĐ][a-zA-ZĐđ]+(?:\\s+[A-ZĐ][a-zA-ZĐđ]+)?)\\b")
                .matcher(cleanedText);
        if (fallbackMatcher.find()) {
            return fallbackMatcher.group(1).replaceAll("[^a-zA-ZĐđ\\s]", "").trim();
        }

        return null;
    }
}