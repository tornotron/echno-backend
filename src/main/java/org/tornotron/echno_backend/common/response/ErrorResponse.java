package org.tornotron.echno_backend.common.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ErrorResponse {
    private int status;
    private String message;
    private String details;
    private LocalDateTime timestamp;
}
