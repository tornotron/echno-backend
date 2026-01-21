package org.tornotron.echno_backend.billing.dto;

import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;

@Value
@Builder
public class PlanFeatureDto {
    Long id;
    String featureCode;
    String featureName;
    FeatureType featureType;
    Boolean enabled;
    Long quotaLimit;
    QuotaPeriod quotaPeriod;
}
