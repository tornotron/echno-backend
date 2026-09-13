package org.tornotron.echno_backend.organization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * The body of a dataset-consent update. A single explicit boolean rather than a toggle, so a
 * retried request cannot flip the flag twice.
 *
 * @param datasetConsent The value to record.
 */
@Schema(description = "Sets the organization's dataset-consent flag")
public record DatasetConsentUpdateDto(
        @NotNull @Schema(description = "True to record written consent, false to withdraw it")
        Boolean datasetConsent) {
}
