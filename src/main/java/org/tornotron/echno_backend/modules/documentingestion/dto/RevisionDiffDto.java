package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What comparing a revised BOQ with the file it replaces proposed: one change per changed field, "
        + "plus items added and removed, each a CHANGE candidate to review.")
public record RevisionDiffDto(int itemsCompared, int changesProposed, int itemsAdded, int itemsRemoved) {
}
