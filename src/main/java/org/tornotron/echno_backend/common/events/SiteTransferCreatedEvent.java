package org.tornotron.echno_backend.common.events;

import org.springframework.context.ApplicationEvent;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;

public class SiteTransferCreatedEvent extends ApplicationEvent {

    private final SiteTransfer siteTransfer;

    public SiteTransferCreatedEvent(Object source, SiteTransfer siteTransfer) {
        super(source);
        this.siteTransfer = siteTransfer;
    }

    public SiteTransfer getSiteTransfer() {
        return siteTransfer;
    }
}
