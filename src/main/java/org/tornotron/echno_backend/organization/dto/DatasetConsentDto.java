package org.tornotron.echno_backend.organization.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The organization's dataset-consent flag as the admin screen reads and writes it.
 *
 * @param organizationId The organization the flag belongs to.
 * @param datasetConsent Whether the organization has consented, in writing, to its inspection
 *                       evidence being exported into the construction image dataset.
 */
@Schema(description = "Per-organization consent to dataset export of inspection evidence")
public record DatasetConsentDto(
        @Schema(description = "The organization the flag belongs to") Long organizationId,
        @Schema(description = "True when the client has given written consent") boolean datasetConsent) {
}
