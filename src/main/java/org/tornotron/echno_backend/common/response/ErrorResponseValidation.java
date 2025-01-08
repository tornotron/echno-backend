package org.tornotron.echno_backend.common.response;

import lombok.Data;

import java.util.Map;

@Data
public class ErrorResponseValidation {
    private String message;
    private Map<String ,String> errors;

    public ErrorResponseValidation(String message,Map<String ,String> errors) {
        this.message = message;
        this.errors = errors;
    }
}
