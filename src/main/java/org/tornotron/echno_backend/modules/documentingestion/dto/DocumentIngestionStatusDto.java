package org.tornotron.echno_backend.modules.documentingestion.dto;

/**
 * What the web app reads to confirm the module is open to the caller's organization.
 *
 * @param moduleId The module id
 * @param version The module version
 */
public record DocumentIngestionStatusDto(String moduleId, String version) {
}
