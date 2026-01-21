package org.tornotron.echno_backend.common.exception;

import lombok.Getter;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;

@Getter
public class SubscriptionAccessDeniedException extends RuntimeException {

    private final FeatureAccessResultDto accessResult;

    private final String featureCode;

    public SubscriptionAccessDeniedException(String message, FeatureAccessResultDto accessResult) {
        super(message);
        this.accessResult = accessResult;
        this.featureCode = null;
    }

    public SubscriptionAccessDeniedException(String message, String featureCode, FeatureAccessResultDto accessResult) {
        super(message);
        this.featureCode = featureCode;
        this.accessResult = accessResult;
    }

    public SubscriptionAccessDeniedException(String message) {
        super(message);
        this.accessResult = null;
        this.featureCode = null;
    }

    public SubscriptionAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
        this.accessResult = null;
        this.featureCode = null;
    }

    public boolean hasAccessResult() {
        return accessResult != null;
    }

    public String getReason() {
        return accessResult != null ? accessResult.getReason() : null;
    }

    public Long getCurrentUsage() {
        return accessResult != null ? accessResult.getCurrentUsage() : null;
    }

    public Long getQuotaLimit() {
        return accessResult != null ? accessResult.getQuotaLimit() : null;
    }
}
