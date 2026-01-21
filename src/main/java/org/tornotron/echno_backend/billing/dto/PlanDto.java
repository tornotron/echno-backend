package org.tornotron.echno_backend.billing.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class PlanDto {
    Long id;
    String code;
    String name;
    String description;
    Integer version;
    BigDecimal monthlyPrice;
    BigDecimal annualPrice;
    String currency;
    Boolean isActive;
    Boolean isPublic;
    Integer trialDays;
    Integer maxUsers;
    Integer sortOrder;
    List<PlanFeatureDto> features;
}
