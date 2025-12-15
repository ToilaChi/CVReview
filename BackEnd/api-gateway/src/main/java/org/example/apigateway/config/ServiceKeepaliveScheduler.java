package org.example.apigateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableScheduling
public class ServiceKeepaliveScheduler {

    private final WebClient webClient;
    private final String authServiceUrl;
    private final String recruitmentServiceUrl;
    private final String aiServiceUrl;
    private final String embeddingServiceUrl;
    private final String chatbotServiceUrl;

    public ServiceKeepaliveScheduler() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024))
                .build();

        this.authServiceUrl = System.getenv("AUTH_SERVICE_URL");
        this.recruitmentServiceUrl = System.getenv("RECRUITMENT_SERVICE_URL");
        this.aiServiceUrl = System.getenv("AI_SERVICE_URL");
        this.embeddingServiceUrl = System.getenv("EMBEDDING_SERVICE_URL");
        this.chatbotServiceUrl = System.getenv("CHATBOT_SERVICE_URL");
    }

    // Ping mỗi 10 phút
    @Scheduled(fixedRate = 600000) // 10 minutes
    public void keepServicesAwake() {
        pingService(authServiceUrl, "Auth Service");
        pingService(recruitmentServiceUrl, "Recruitment Service");
        pingService(aiServiceUrl, "AI Service");
        pingService(embeddingServiceUrl, "Embedding Service");
        pingService(chatbotServiceUrl, "Chatbot Service");
    }

    private void pingService(String baseUrl, String serviceName) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            log.warn("{} URL not configured", serviceName);
            return;
        }

        String healthUrl = baseUrl + "/actuator/health";

        webClient.get()
                .uri(healthUrl)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response ->
                        log.info("✓ {} is awake - {}", serviceName, healthUrl))
                .doOnError(error ->
                        log.error("✗ Failed to ping {} - {}: {}",
                                serviceName, healthUrl, error.getMessage()))
                .onErrorResume(error -> Mono.empty())
                .subscribe();
    }

    // Ping lần đầu khi khởi động (sau 2 phút)
    @Scheduled(initialDelay = 120000, fixedDelay = Long.MAX_VALUE)
    public void initialWarmup() {
        log.info("Starting initial service warmup...");
        keepServicesAwake();
    }
}