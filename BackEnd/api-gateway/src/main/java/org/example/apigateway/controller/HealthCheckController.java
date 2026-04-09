package org.example.apigateway.controller;

import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/system")
public class HealthCheckController {

    private final WebClient webClient;

    public HealthCheckController(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Value("${DOMAIN_API-GATEWAY:http://localhost:8080}")
    private String gatewayUrl;

    @Value("${DOMAIN_AUTH-SERVICE:http://localhost:8081}")
    private String authUrl;

    @Value("${DOMAIN_RECRUITMENT-SERVICE:http://localhost:8082}")
    private String recruitmentUrl;

    @Value("${DOMAIN_AI-SERVICE:http://localhost:8083}")
    private String aiUrl;

    @Value("${DOMAIN_EMBEDDING-SERVICE:http://localhost:8084}")
    private String embeddingUrl;

    @Value("${DOMAIN_CHATBOT-SERVICE:http://localhost:8085}")
    private String chatbotUrl;

    @GetMapping("/health")
    public Mono<ApiResponse<Map<String, String>>> checkSystemHealth() {
        Map<String, String> urls = new HashMap<>();
        urls.put("api-gateway", gatewayUrl + "/actuator/health");
        urls.put("auth-service", authUrl + "/actuator/health");
        urls.put("recruitment-service", recruitmentUrl + "/actuator/health");
        urls.put("ai-service", aiUrl + "/actuator/health");
        urls.put("embedding-service", embeddingUrl + "/health");
        urls.put("chatbot-service", chatbotUrl + "/chatbot/health");

        return Flux.fromIterable(urls.entrySet())
                .flatMap(entry -> webClient.get()
                        .uri(entry.getValue())
                        .retrieve()
                        .bodyToMono(String.class)
                        .map(res -> {
                            if (res.contains("\"status\":\"UP\"") || res.contains("\"status\":\"healthy\"")
                                    || res.contains("\"status\": \"healthy\"")) {
                                return "UP";
                            }
                            return "UP"; // fallback as long as it returned 200 OK
                        })
                        .onErrorReturn("DOWN")
                        .map(status -> Map.entry(entry.getKey(), status))
                        .timeout(Duration.ofSeconds(5))
                        .onErrorReturn(Map.entry(entry.getKey(), "DOWN")))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(stats -> new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "System health retrieved", stats));
    }
}
