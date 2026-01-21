package org.tornotron.echno_backend.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SubscriptionErrorResponse {
    private String error;
    private String message;
    private Map<String , Object> details;
    private Instant timestamp;
    private String path;
}
