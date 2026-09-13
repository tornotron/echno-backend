package org.tornotron.echno_backend.billing.gateway.dto;

/**
 * Where the pre-debit notification goes. RBI requires the payer to be told at least 24 hours
 * before every recurring debit, so a subscription cannot be created without at least one
 * channel; {@link org.tornotron.echno_backend.billing.gateway.MandatePolicy} enforces that.
 */
public record NotifyInfo(String email, String phone) {

    public boolean hasChannel() {
        return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
    }
}
