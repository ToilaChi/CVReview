package org.example.apigateway.security;

import lombok.Getter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Predicate;

@Component
public class RouterValidator {
    @Getter
    private static final List<String> openEndpoints = List.of(
            "/auth/login",
            "/auth/logout",
            "/actuator/health",
            "/actuator/info"
    );

    public Predicate<ServerHttpRequest> isSecured = request -> {
        String path = request.getURI().getPath();
        System.out.println("RouterValidator checking path: " + path);

        // Kiểm tra xem path có bắt đầu bằng bất kỳ endpoint nào trong danh sách không
        boolean isOpenEndpoint = openEndpoints.stream()
                .anyMatch(path::startsWith);

        if (isOpenEndpoint) {
            System.out.println("Open endpoint detected: " + path);
            return false;
        }

        // Tất cả các endpoint khác đều cần authentication
        System.out.println("Secured endpoint detected: " + path);
        return true;
    };
}