package org.tornotron.echno_backend.finance.invoice;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Control-account codes used when posting the journal entry for an issued invoice.
 * Tunable per environment via {@code finance.invoice.*} without code changes.
 *
 * <p>Both must reference postable (leaf) accounts in the chart of accounts:
 * <ul>
 *   <li>{@code arAccountCode} - Accounts Receivable, debited for the invoice total.</li>
 *   <li>{@code gstOutputCode} - GST Output Payable, credited for the tax total.</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "finance.invoice")
public class InvoicePostingProperties {

    /** Accounts Receivable control account (postable leaf). */
    private String arAccountCode = "1200";

    /** GST Output Payable control account (postable leaf). */
    private String gstOutputCode = "2210";
}
