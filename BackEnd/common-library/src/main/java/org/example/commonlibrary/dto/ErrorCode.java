package org.example.commonlibrary.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    SUCCESS(200, "Success", HttpStatus.OK),

    // Auth errors
    UNAUTHORIZED(1001, "Unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(1002, "Forbidden", HttpStatus.FORBIDDEN),
    USER_NOT_FOUND(1003, "User not found", HttpStatus.NOT_FOUND),
    INVALID_CREDENTIALS(1004, "Invalid username or password", HttpStatus.BAD_REQUEST),

    // CV errors
    CV_NOT_FOUND(2001, "CV not found", HttpStatus.NOT_FOUND),
    CV_PARSE_FAILED(2002, "Failed to parse CV", HttpStatus.BAD_REQUEST),
    DUPLICATE_CV(2003, "Duplicate CV upload", HttpStatus.CONFLICT),

    // Position errors
    POSITION_NOT_FOUND(3001, "Position not found", HttpStatus.NOT_FOUND),
    DUPLICATE_POSITION(3002, "Position already exists", HttpStatus.CONFLICT),
    FILE_NOT_FOUND(3003, "File not found", HttpStatus.NOT_FOUND),
    FILE_PARSE_FAILED(3004, "Failed to parse JD", HttpStatus.BAD_REQUEST),
    FAILED_SAVE_FILE(3005, "Failed to save file", HttpStatus.INTERNAL_SERVER_ERROR),

    // Review errors
    REVIEW_FAILED(4001, "AI review failed", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
