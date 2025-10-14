package org.example.commonlibrary.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@Builder
public class ApiResponse<T> {
    private final int statusCode;
    private final String message;
    private final T data;
    private final LocalDateTime timestamp;

    public ApiResponse(int statusCode, String message, T data) {
        this.statusCode = statusCode;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now();
    }

    public ApiResponse(int statusCode, String message) {
        this(statusCode, message, null);
    }
}
