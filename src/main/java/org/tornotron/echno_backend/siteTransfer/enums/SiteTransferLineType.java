package org.tornotron.echno_backend.siteTransfer.enums;

/**
 * What a site transfer line carries.
 *
 * <p>One transfer can hold both kinds, so a machine going to a site travels on the same document
 * as the materials going with it, with the same receipt, cancellation, reversal and status trail.
 */
public enum SiteTransferLineType {

    /** A quantity of a material, drawn from one stock balance and credited to another. */
    MATERIAL,

    /**
     * One asset from the asset register. Always a single unit. Moving it writes an entry on the
     * asset's movement ledger rather than a stock movement.
     */
    ASSET
}
