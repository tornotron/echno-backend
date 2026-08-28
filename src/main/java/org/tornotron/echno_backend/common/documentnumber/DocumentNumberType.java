package org.tornotron.echno_backend.common.documentnumber;

/**
 * The document families that carry a server-allocated number, and the prefix each one wears.
 *
 * <p>The name of the constant is what goes into the {@code document_type} column of
 * {@code document_number_sequence}, so renaming one orphans that tenant's counter and starts
 * its numbering again from one. Add constants, do not rename them.
 *
 * <p>The prefixes are the ones the browser used to generate, kept identical so numbers issued
 * before and after the move to server-side allocation sort and read the same way.
 */
public enum DocumentNumberType {

    /** Site transfers, {@code TRF-2026-000001}. */
    SITE_TRANSFER("TRF"),

    /** Purchase orders, {@code PO-2026-000001}. */
    PURCHASE_ORDER("PO"),

    /** Material indents, {@code IND-2026-000001}. */
    INDENT("IND"),

    /** Goods received notes, {@code GRN-2026-000001}. */
    GOODS_RECEIVED_NOTE("GRN");

    private final String prefix;

    DocumentNumberType(String prefix) {
        this.prefix = prefix;
    }

    public String getPrefix() {
        return prefix;
    }
}
