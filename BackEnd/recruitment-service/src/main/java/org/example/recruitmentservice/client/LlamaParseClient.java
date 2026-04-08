package org.example.recruitmentservice.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.core.ParameterizedTypeReference;
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

@Slf4j
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
            log.debug("API Key: {}", (apiKey != null ? "exists" : "null"));
            log.debug("File path: {}", filePath);

            String jobId = uploadFileForJD(filePath);
            log.debug("Job ID: {}", jobId);

            // Poll result
            String parsedText = pollResult(jobId);
            log.debug("Parse completed!");

            return parsedText;

        } catch (Exception e) {
            log.error("Parse failed: {}", e.getMessage(), e);
            throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
        }
    }

    /**
     * RabbitMQ Listener - Parse CV từ Drive.
     *
     * Lưu ý: method này KHÔNG có @Transactional để tránh giữ DB Connection mở
     * trong suốt 75 giây chờ LlamaParse API.
     * Các thao tác ghi DB được đóng gói vào các helper method @Transactional nhỏ.
     */
    @RabbitListener(queues = RabbitMQConfig.CV_UPLOAD_QUEUE, containerFactory = "cvParsingContainerFactory")
    public void parseCV(CVUploadEvent event) {
        int cvId = event.getCvId();
        String tempFilePath = null;

        try {
            // Guard: skip if CV already terminated — happens when a stale requeued message
            // is picked up after the DLQ listener already marked this CV as FAILED.
            CandidateCV currentState = candidateCVRepository.findById(cvId).orElse(null);
            if (currentState == null) {
                log.warn("[PARSE] CV {} not found in DB, discarding message", cvId);
                return;
            }
            if (currentState.getCvStatus() == CVStatus.FAILED) {
                log.warn("[PARSE] CV {} already FAILED, discarding stale requeued message", cvId);
                return;
            }

            // [Transaction 1] Mark PARSING
            markCvAsParsing(cvId);

            // Download file từ Drive về temp (ngoài transaction)
            String fileId = event.getFileId();
            tempFilePath = storageService.downloadFileToTemp(fileId);

            File file = new File(tempFilePath);
            if (!file.exists()) {
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
            }

            // Gọi LlamaParse API và polling (mất 10-75s) - KHÔNG giữ transaction
            String jobId = uploadFileForCV(tempFilePath);
            String parsedText = pollResult(jobId);

            // Extract information
            String extractedName = extractName(parsedText);
            String extractedEmail = extractEmail(parsedText);

            // Load CV kèm Position bằng JOIN FETCH - Position luôn được init, không có Lazy
            // proxy
            CandidateCV cvForChunking = candidateCVRepository.findByIdWithPosition(cvId)
                    .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

            // Chunking (CPU-bound, ngoài transaction)
            log.info("Starting chunking for CV: {}", cvId);
            List<ChunkPayload> chunks = chunkingService.chunk(cvForChunking, parsedText);

            if (chunks == null || chunks.isEmpty()) {
                log.warn("No chunks generated for CV {}", cvId);
                throw new CustomException(ErrorCode.CV_CHUNKING_FAILED);
            }

            log.info("Generated {} chunks for CV: {}", chunks.size(), cvId);

            // [Transaction 2] Lưu kết quả parse - re-fetch trong session rìi của nó
            saveParsedCvResult(cvId, parsedText, extractedName, extractedEmail);

            // Update batch
            processingBatchService.incrementProcessed(event.getBatchId(), true);

            log.info("CV parsed successfully - ID: {} | Name: {} | Email: {} | Source: {} | Position: {}",
                    cvId, extractedName, extractedEmail, cvForChunking.getSourceType(),
                    (cvForChunking.getPosition() != null ? cvForChunking.getPosition().getId() : "N/A"));

            // publish event (sau khi Transaction 2 đã commit)
            publishCVChunkedEvent(cvForChunking, chunks);

        } catch (Exception e) {
            log.error("CV parse failed for cvId {}: {}", cvId, e.getMessage(), e);
            // Re-throw so RabbitMQ routes this message to cv.upload.dlq.
            // CVUploadDlqListener is the single owner of FAILED state + SSE notification.
            throw new RuntimeException("CV parse failed: " + e.getMessage(), e);
        } finally {
            if (tempFilePath != null) {
                storageService.deleteTempFile(tempFilePath);
            }
        }
    }

    /** [T1] Re-fetch CV và đổi status sang PARSING trong 1 transaction. */

    @Transactional
    public void markCvAsParsing(int cvId) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));
        cv.setCvStatus(CVStatus.PARSING);
        candidateCVRepository.save(cv);
    }

    /** [T2] Re-fetch CV và lưu toàn bộ kết quả parse trong 1 transaction. */
    @Transactional
    public void saveParsedCvResult(int cvId, String parsedText, String name, String email) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));
        cv.setCvContent(parsedText);
        if (name != null)
            cv.setName(name);
        if (email != null)
            cv.setEmail(email);
        cv.setCvStatus(CVStatus.PARSED);
        cv.setParsedAt(LocalDateTime.now());
        cv.setUpdatedAt(LocalDateTime.now());
        cv.setErrorMessage(null);
        cv.setFailedAt(null);
        candidateCVRepository.save(cv);
    }

    // HELPER METHODS

    /**
     * Upload file cho JD - Config đơn giản, chỉ cần parse đầy đủ text và giữ
     * structure
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

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://api.cloud.llamaindex.ai/api/parsing/upload",
                HttpMethod.POST,
                request,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });

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
                "Extract all text from this CV/Resume into Markdown exactly. Preserve tables as markdown tables. Extract text and context from informative images or diagrams. Consistently use Markdown headers (#, ##, ###) for standard CV sections.");
        body.add("result_type", "markdown");
        body.add("target_pages", "");
        body.add("invalidate_cache", "true");
        body.add("gpt4o_mode", "true");
        body.add("skip_diagonal_text", "true");
        body.add("extract_all_pages", "true");
        body.add("do_not_unroll_columns", "false");
        body.add("page_separator", "false");
        body.add("prefix_or_suffix", "false");
        body.add("continuous_mode", "true");
        body.add("fast_mode", "false");

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://api.cloud.llamaindex.ai/api/parsing/upload",
                HttpMethod.POST,
                request,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });

        return (String) response.getBody().get("id");
    }

    /**
     * Poll kết quả parse từ LlamaParse API.
     *
     * Chiến lược poll:
     * - Mỗi 3 giây check status 1 lần, tối đa 25 lần = tổng cộng tối đa ~75 giây.
     * - Nếu LlamaParse trả về ERROR → ném exception ngay (lỗi thực sự, vào DLQ).
     * - Nếu hết 75 giây vẫn PENDING/PROCESSING → ném RuntimeException (timeout).
     * RabbitMQ sẽ re-queue/retry thông minh theo cấu hình DLX.
     */
    private String pollResult(String jobId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        String statusUrl = "https://api.cloud.llamaindex.ai/api/parsing/job/" + jobId;
        String resultUrl = "https://api.cloud.llamaindex.ai/api/parsing/job/" + jobId + "/result/markdown";

        final int MAX_POLLS = 25; // 25 lần x 3s = 75s tối đa
        final int POLL_INTERVAL_MS = 3000;

        for (int i = 0; i < MAX_POLLS; i++) {
            try {
                ResponseEntity<Map<String, Object>> statusResponse = restTemplate.exchange(
                        statusUrl, HttpMethod.GET, request,
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        });

                Map<String, Object> statusBody = statusResponse.getBody();
                if (statusBody == null) {
                    log.warn("Poll #{} - Empty status body, retrying...", i + 1);
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                String status = (String) statusBody.get("status");
                log.debug("Poll #{}/{} - Status: {} | JobId: {}", i + 1, MAX_POLLS, status, jobId);

                if ("SUCCESS".equals(status)) {
                    ResponseEntity<Map<String, Object>> resultResponse = restTemplate.exchange(
                            resultUrl, HttpMethod.GET, request,
                            new ParameterizedTypeReference<Map<String, Object>>() {
                            });

                    Map<String, Object> resultBody = resultResponse.getBody();
                    if (resultBody != null && resultBody.containsKey("markdown")) {
                        String markdown = (String) resultBody.get("markdown");
                        if (markdown != null) {
                            markdown = markdown.trim();
                            markdown = markdown.replaceAll("^```markdown\\s*", "");
                            markdown = markdown.replaceAll("\\s*```$", "");
                            markdown = markdown.trim();
                        }
                        log.info("Parse completed! Text length: {}", markdown.length());
                        return markdown;
                    }
                    // Trả về SUCCESS nhưng không có markdown -> coi như lỗi hard
                    throw new CustomException(ErrorCode.FILE_PARSE_FAILED);

                } else if ("ERROR".equals(status)) {
                    // LlamaParse xác nhận lỗi rõ ràng -> vào DLQ ngay, không retry
                    log.error("LlamaParse reported ERROR for job: {}", jobId);
                    throw new CustomException(ErrorCode.FILE_PARSE_FAILED);
                }
                // PENDING / PROCESSING -> continue polling

            } catch (CustomException e) {
                throw e; // Re-throw lỗi hard, không bọc ngoài
            } catch (Exception e) {
                // Lỗi mạng/timeout khi gọi LlamaParse API -> log và thử lại poll
                log.warn("Poll #{} network error: {}", i + 1, e.getMessage());
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }

        // Hết số lần poll mà chưa xong -> timeout
        // Ném RuntimeException để RabbitMQ xử lý re-queue theo cấu hình DLX
        log.error("LlamaParse polling timed out (75s) for jobId: {}", jobId);
        throw new RuntimeException("LlamaParse parse timeout after " + MAX_POLLS + " polls for job: " + jobId);
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
                totalTokens);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                rabbitTemplate.convertAndSend(
                                        RabbitMQConfig.CV_CHUNKED_EXCHANGE,
                                        RabbitMQConfig.CV_CHUNKED_ROUTING_KEY,
                                        event);
                                log.info("Published CV chunked event for CV: {} with {} chunks",
                                        cv.getId(), chunks.size());
                            } catch (Exception e) {
                                log.error("Failed to publish CV chunked event: {}", e.getMessage(), e);
                            }
                        }
                    });
        } else {
            // Fallback if no transaction (shouldn't happen with @Transactional)
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.CV_CHUNKED_EXCHANGE,
                        RabbitMQConfig.CV_CHUNKED_ROUTING_KEY,
                        event);
                log.info("Published CV chunked event (no tx) for CV: {}", cv.getId());
            } catch (Exception e) {
                log.error("Failed to publish CV chunked event (no tx): {}", e.getMessage(), e);
            }
        }
    }

    // Regex extract email
    private String extractEmail(String text) {
        if (text == null || text.isEmpty())
            return null;

        Pattern emailPattern = Pattern.compile(
                "(?i)(?:email|mail)[:\\s]*([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
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
        if (text == null || text.isEmpty())
            return null;

        // Loại bỏ ký tự đặc biệt cơ bản trong text
        String cleanedText = text.replaceAll("[#%@\\-!$^&*()_+=\\[\\]{}|\\\\;:\"'<>,/?~`]", " ");

        // Regex chính – tìm các dạng "Name:", "Full Name:", "Họ tên:", "Fullname:"
        Pattern namePattern = Pattern.compile(
                "(?i)(?:^|\\b)(?:name|full name|họ tên)[:\\s]+([A-ZĐ][a-zA-ZĐđ\\s]+?)(?=\\b(?:date|dob|birth|email|phone|address|\\r?\\n|$))");
        Matcher matcher = namePattern.matcher(cleanedText);
        if (matcher.find()) {
            String name = matcher.group(1).trim();

            // Loại bỏ các phần thừa (nếu có)
            name = name.replaceAll("(?i)\\b(date of birth|dob|email|phone|address).*", "").trim();

            // Loại bỏ các ký tự không phải chữ và space
            name = name.replaceAll("[^a-zA-ZĐđ\\s]", "").trim();

            // Giới hạn độ dài hợp lý
            if (name.length() > 50)
                name = name.substring(0, 50).trim();
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
        Matcher fallbackMatcher = Pattern
                .compile("\\b([A-ZĐ][a-zA-ZĐđ]+\\s+[A-ZĐ][a-zA-ZĐđ]+(?:\\s+[A-ZĐ][a-zA-ZĐđ]+)?)\\b")
                .matcher(cleanedText);
        if (fallbackMatcher.find()) {
            return fallbackMatcher.group(1).replaceAll("[^a-zA-ZĐđ\\s]", "").trim();
        }

        return null;
    }
}