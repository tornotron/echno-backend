package org.tornotron.echno_backend.siteTransfer.enums;

/**
 * Where a site transfer stands between the material leaving one place and arriving at another.
 *
 * <p>Every one of these now follows from a movement rather than from a payload. A transfer that
 * crosses a project boundary is issued {@link #PENDING} with only its outbound leg written, and
 * reaches {@link #PARTIALLY_TRANSFERRED} or {@link #COMPLETED} when somebody at the receiving
 * site records what arrived. A transfer between two stores on one project never leaves that
 * site's custody, so it is written complete and is {@link #COMPLETED} from the moment it exists.
 * {@link #CANCELLED} is a transfer abandoned in transit, and reaching it returns the stock to
 * the sending site.
 */
public enum SiteTransferStatus {

    /**
     * Issued and not yet received. The material has left the sending site and nothing has been
     * recorded as arriving.
     *
     * <p>The quantity is in transit: it is off the sending site's balance and not yet on the
     * receiving site's, so an organization-wide total of on-hand stock is short by it. That is
     * the truth about material sitting on a lorry, and a report summing stock across projects
     * should show it as a labelled in-transit figure beside the total rather than pretending it
     * is somewhere.
     */
    PENDING,

    /**
     * Some of what was sent has been recorded as arriving and some has not.
     *
     * <p>The difference is an open variance: the sending project is down the full quantity
     * against this transfer, the receiving project is up what arrived, and the rest is
     * unaccounted for. The transfer does not write a loss movement of its own to close it. A
     * loss written automatically is a stock correction nobody authorised, and a stock adjustment
     * naming this transfer is where that decision belongs.
     */
    PARTIALLY_TRANSFERRED,

    /** Everything that was sent has been recorded as arriving. */
    COMPLETED,

    /**
     * Abandoned before it was received, with the outbound leg reversed.
     *
     * <p>Reachable only from {@link #PENDING}, and only through the cancel endpoint, which
     * returns the stock to the sending project and location it was drawn from. Without the
     * reversal a transfer abandoned in transit would leave the sending project permanently
     * short with no way back, which would make the two-step document worse than the one-step
     * one it replaces. A transfer that has already had something received against it cannot be
     * cancelled: what arrived is at the far site, and deciding its fate is an adjustment rather
     * than a reversal.
     */
    CANCELLED
}
