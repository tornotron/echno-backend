package org.tornotron.echno_backend.billing.dto;

import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.enums.FeatureType;

@Value
@Builder
public class FeatureDto {
    Long id;
    String code;
    String name;
    String description;
    FeatureType featureType;
    String category;
    Boolean isActive;
}
