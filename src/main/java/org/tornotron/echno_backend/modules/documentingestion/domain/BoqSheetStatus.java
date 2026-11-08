package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * Where a BOQ sheet is. {@code DETECTED}: a header was found and a mapping proposed.
 * {@code NEEDS_MAPPING}: no usable header was found, so a person maps the columns.
 * {@code EXTRACTED}: a person confirmed a mapping and the rows were read into candidates.
 */
public enum BoqSheetStatus {
    DETECTED,
    NEEDS_MAPPING,
    EXTRACTED
}
