package org.tornotron.echno_backend.common.events;

import org.springframework.context.ApplicationEvent;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;

import java.util.List;

/**
 * Raised when a transfer is abandoned before anything was received against it.
 *
 * <p>The listener returns each line's sent quantity to the sending project and location it was
 * drawn from. Without that reversal the sending project would stay permanently short for a
 * lorry that turned back, and the only way to correct it would be a stock adjustment reading as
 * an unexplained count variance.
 */
public class SiteTransferCancelledEvent extends ApplicationEvent {

    private final SiteTransfer siteTransfer;
    private final List<SiteTransferItem> items;
    private final Employee cancelledBy;
    private final String reason;

    public SiteTransferCancelledEvent(Object source, SiteTransfer siteTransfer,
                                      List<SiteTransferItem> items, Employee cancelledBy,
                                      String reason) {
        super(source);
        this.siteTransfer = siteTransfer;
        this.items = List.copyOf(items);
        this.cancelledBy = cancelledBy;
        this.reason = reason;
    }

    public SiteTransfer getSiteTransfer() {
        return siteTransfer;
    }

    public List<SiteTransferItem> getItems() {
        return items;
    }

    /** The person who cancelled it, resolved from the session rather than the payload. */
    public Employee getCancelledBy() {
        return cancelledBy;
    }

    public String getReason() {
        return reason;
    }
}
