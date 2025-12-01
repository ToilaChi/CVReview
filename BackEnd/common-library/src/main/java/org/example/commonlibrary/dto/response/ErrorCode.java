package org.example.commonlibrary.dto.response;

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
    UNAUTHORIZED_ACTION(1005, "Unauthorized action", HttpStatus.UNAUTHORIZED),
    INVALID_EMAIL(1006, "Invalid email", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST(1007, "Refresh token is required", HttpStatus.BAD_REQUEST),

    // CV errors
    CV_NOT_FOUND(2001, "CV not found", HttpStatus.NOT_FOUND),
    CV_PARSE_FAILED(2002, "Failed to parse CV", HttpStatus.BAD_REQUEST),
    DUPLICATE_CV(2003, "Duplicate CV upload", HttpStatus.CONFLICT),
    CV_ALREADY_PROCESSING(2004, "CV already processing", HttpStatus.CONFLICT),
    CV_NOT_FAILED(2005, "CV not failed", HttpStatus.CONFLICT),
    NO_FAILED_CVS_IN_BATCH(2006, "No failed CVs in batch", HttpStatus.NOT_FOUND),
    CVS_NOT_SAME_POSITION(2007, "CVs not same position", HttpStatus.CONFLICT),

    // Position errors
    POSITION_NOT_FOUND(3001, "Position not found", HttpStatus.NOT_FOUND),
    DUPLICATE_POSITION(3002, "Position already exists", HttpStatus.CONFLICT),
    FILE_NOT_FOUND(3003, "File not found", HttpStatus.NOT_FOUND),
    FILE_PARSE_FAILED(3004, "Failed to parse JD", HttpStatus.INTERNAL_SERVER_ERROR),
    FAILED_SAVE_FILE(3005, "Failed to save file", HttpStatus.INTERNAL_SERVER_ERROR),
    CAN_NOT_DELETE_POSITION(3006, "Cannot delete position because it contains CVs", HttpStatus.BAD_REQUEST),
    MISSING_NAME_AND_LEVEL(3007, "Missing name and level", HttpStatus.BAD_REQUEST),

    // Review errors
    REVIEW_FAILED(4001, "AI review failed", HttpStatus.INTERNAL_SERVER_ERROR),

    // File errors
    FILE_DELETE_FAILED(5001, "File can not delete", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_MOVE_FAILED(5002, "File move failed", HttpStatus.INTERNAL_SERVER_ERROR),

    // Processing batch
    BATCH_NOT_FOUND(6001, "Batch not found", HttpStatus.NOT_FOUND),

    // Retry
    RETRY_FAILED(7001, "Retry failed", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
