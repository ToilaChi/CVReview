package org.example.apigateway.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RouterValidator routerValidator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        System.out.println("Gateway Filter processing: " + path);

        // Kiểm tra xem endpoint này có cần xác thực không
        boolean needsAuth = routerValidator.isSecured.test(request);
        System.out.println("Path " + path + " needs authentication: " + needsAuth);

        if (needsAuth) {
            return handleAuthentication(exchange, chain);
        }

        // Nếu không cần xác thực, chỉ cần forward request
        System.out.println("Open endpoint, forwarding: " + path);
        return chain.filter(exchange);
    }

    private Mono<Void> handleAuthentication(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // Kiểm tra authorization header
        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            System.out.println("Missing authorization header for: " + path);
            return onError(exchange, "Missing authorization header", HttpStatus.UNAUTHORIZED);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            System.out.println("Invalid authorization header format for: " + path);
            return onError(exchange, "Invalid authorization header format", HttpStatus.UNAUTHORIZED);
        }

        // Xác thực JWT
        String token = authHeader.substring(7);
        try {
            // Xác thực token
            if (!jwtUtil.validateToken(token)) {
                System.out.println("Invalid JWT token for: " + path);
                return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
            }

            // Extract thông tin và thêm vào header
            String phone = jwtUtil.extractPhone(token);
            String role = jwtUtil.extractRole(token);

            System.out.println("JWT valid for " + path + " - Phone: " + phone + ", Role: " + role);

            // Thêm thông tin người dùng vào headers để các service sau có thể sử dụng
            ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                    .header("X-User-Phone", phone)
                    .header("X-User-Role", role)
                    .build();

            return chain.filter(exchange.mutate().request(modifiedRequest).build());

        } catch (Exception e) {
            System.out.println("Token validation failed for " + path + ": " + e.getMessage());
            return onError(exchange, "Invalid token: " + e.getMessage(), HttpStatus.UNAUTHORIZED);
        }
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);
        response.getHeaders().add("Content-Type", "application/json");

        String responseBody = String.format(
                "{\"status\": %d, \"message\": \"%s\", \"timestamp\": \"%s\"}",
                httpStatus.value(),
                err,
                java.time.Instant.now().toString()
        );

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(responseBody.getBytes()))
        );
    }

    @Override
    public int getOrder() {
        return -100; // Đảm bảo filter này chạy trước các filter khác
    }
}