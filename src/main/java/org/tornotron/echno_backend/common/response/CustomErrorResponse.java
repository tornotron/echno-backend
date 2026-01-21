package org.tornotron.echno_backend.common.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class CustomErrorResponse {
    private int status;
    private String message;
    private String details;
    private LocalDateTime timestamp;
}
