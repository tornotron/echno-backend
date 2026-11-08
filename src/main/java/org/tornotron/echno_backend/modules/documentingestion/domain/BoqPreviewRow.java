package org.tornotron.echno_backend.modules.documentingestion.domain;

import java.util.List;

/**
 * One row of the top of a sheet, as shown on the column-mapping screen, so a person can see which
 * column is which before any row is read.
 */
public record BoqPreviewRow(int row, boolean hidden, List<PreviewCell> cells) {

    public BoqPreviewRow {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }

    /** A cell's column number, reference and shown text, cut to a readable length. */
    public record PreviewCell(int col, String ref, String text) {
    }
}
