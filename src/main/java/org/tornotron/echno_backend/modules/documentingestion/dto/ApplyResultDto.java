package org.tornotron.echno_backend.modules.documentingestion.dto;

/**
 * The outcome of one apply call. {@code remaining} above zero means the call stopped at its batch
 * limit and a second call carries on from there; nothing applied is applied twice.
 */
public record ApplyResultDto(int applied, int failed, long remaining) {
}
