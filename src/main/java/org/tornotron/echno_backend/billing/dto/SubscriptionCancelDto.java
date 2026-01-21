package org.tornotron.echno_backend.billing.dto;

import lombok.Data;

@Data
public class SubscriptionCancelDto {

    private boolean immediate = false;

    private String reason;
}
