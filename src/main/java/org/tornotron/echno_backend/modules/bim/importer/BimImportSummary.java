package org.tornotron.echno_backend.modules.bim.importer;

/** What one ingestion did to the element table. */
public record BimImportSummary(int inserted, int updated, int retired) {

    public int seen() {
        return inserted + updated;
    }
}
