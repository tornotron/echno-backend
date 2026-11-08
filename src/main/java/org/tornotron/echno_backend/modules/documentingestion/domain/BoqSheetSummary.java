package org.tornotron.echno_backend.modules.documentingestion.domain;

import java.util.List;

/**
 * What reading one BOQ sheet found. Every non-empty row below the header is accounted for: as an
 * item, a section heading, a total, or an ignored row listed by number, so no row is lost silently.
 *
 * @param rowsRead Non-empty rows below the header
 * @param items Rows proposed as BOQ items
 * @param sections Section headings
 * @param totals Total and carried-forward rows, checked and not proposed
 * @param flaggedItems Items carrying at least one flag
 * @param ignoredRows Rows neither item, section nor total, such as a repeated header; at most 500 listed
 * @param warnings Sheet-level findings
 */
public record BoqSheetSummary(int rowsRead, int items, int sections, int totals, int flaggedItems,
                              List<Integer> ignoredRows, List<BoqWarning> warnings) {

    public BoqSheetSummary {
        ignoredRows = ignoredRows == null ? List.of() : List.copyOf(ignoredRows);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
