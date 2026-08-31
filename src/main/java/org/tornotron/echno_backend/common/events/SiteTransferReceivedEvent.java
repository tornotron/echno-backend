package org.tornotron.echno_backend.common.events;

import org.springframework.context.ApplicationEvent;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Raised when somebody records what arrived against a transfer that was in transit.
 *
 * <p>Carries the quantity each line gained in this receipt, not the running total, because the
 * listener writes one inbound movement per line for what arrived now. A second delivery against
 * the same transfer raises a second event carrying only its own quantities, so the ledger reads
 * as two arrivals rather than one restated total.
 */
public class SiteTransferReceivedEvent extends ApplicationEvent {

    /**
     * One line and how much of it arrived in this receipt.
     *
     * @param item     The transfer line, carrying its material.
     * @param quantity How much arrived now. Always greater than zero; a line recorded as
     *                 receiving nothing raises no movement and is left out.
     */
    public record ReceivedLine(SiteTransferItem item, int quantity) {
    }

    private final SiteTransfer siteTransfer;
    private final List<ReceivedLine> receivedLines;
    private final Employee receivedBy;
    private final LocalDateTime receivedOn;
    private final String remarks;

    public SiteTransferReceivedEvent(Object source, SiteTransfer siteTransfer,
                                     List<ReceivedLine> receivedLines, Employee receivedBy,
                                     LocalDateTime receivedOn, String remarks) {
        super(source);
        this.siteTransfer = siteTransfer;
        this.receivedLines = List.copyOf(receivedLines);
        this.receivedBy = receivedBy;
        this.receivedOn = receivedOn;
        this.remarks = remarks;
    }

    public SiteTransfer getSiteTransfer() {
        return siteTransfer;
    }

    public List<ReceivedLine> getReceivedLines() {
        return receivedLines;
    }

    /** The person who confirmed the delivery, resolved from the session rather than the payload. */
    public Employee getReceivedBy() {
        return receivedBy;
    }

    public LocalDateTime getReceivedOn() {
        return receivedOn;
    }

    public String getRemarks() {
        return remarks;
    }
}
