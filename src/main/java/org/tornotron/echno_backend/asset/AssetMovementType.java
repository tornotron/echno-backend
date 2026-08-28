package org.tornotron.echno_backend.asset;

/**
 * What a row in the asset movement ledger records.
 *
 * <p>Unlike the asset's own type, category and status, this value is never supplied
 * free-form by the web client: it names a kind of ledger entry rather than a label on
 * a screen, so it is an enum and the set is closed.
 */
public enum AssetMovementType {

    /**
     * The opening entry for an asset: where it was when it first entered the register,
     * or when the ledger itself was introduced. Every asset has at most one.
     */
    REGISTRATION,

    /** The asset physically moved: its project, its storage location, or both changed. */
    TRANSFER,

    /** The asset stayed put and changed hands: a new custodian took responsibility for it. */
    ASSIGNMENT,

    /**
     * An earlier entry was wrong and is being restated. The ledger is append-only, so a
     * correction is a new row that names the row it corrects rather than an edit of it.
     */
    CORRECTION
}
