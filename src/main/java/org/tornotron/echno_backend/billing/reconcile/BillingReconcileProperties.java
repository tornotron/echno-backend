package org.tornotron.echno_backend.billing.reconcile;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code echno.billing.reconcile.*}: the scheduled reconciliation sweep. Follows the module
 * kill-switch convention: on by default, {@code enabled=false} removes the bean, and with
 * {@code echno.billing.provider=none} the pass is a no-op because there is nothing to fetch
 * from.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "echno.billing.reconcile")
public class BillingReconcileProperties {

    /** Whether the sweep bean exists at all. */
    private boolean enabled = true;

    /** When it runs; hourly on the hour by default. */
    private String cron = "0 0 * * * *";

    private String zone = "UTC";

    /** How many stale subscriptions one pass fetches from the provider. */
    private int maxPerRun = 200;

    /** How many RECEIVED or FAILED inbox rows one pass hands back to the projector. */
    private int retryBatch = 100;

    /** How long a row may sit INCOMPLETE before the provider is asked what became of it. */
    private int incompleteAfterMinutes = 60;

    /**
     * How many days back a CANCELED provider row is still re-read from the provider, so a
     * cancellation the provider did not take (it keeps charging) is re-sent, and a row this
     * side cancelled on a mandate event the provider disagrees with is repaired.
     */
    private int canceledLookbackDays = 14;
}
