package org.tornotron.echno_backend.common.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ErrorResponseValidation {
    private int status;
    private String message;
    private Map<String ,String> errors;
    private String details;
    private LocalDateTime timestamp;

    public ErrorResponseValidation(int status, String message, Map<String, String> errors, String details, LocalDateTime timestamp) {
        this.status = status;
        this.message = message;
        this.errors = errors;
        this.details = details;
        this.timestamp = timestamp;
    }
}
