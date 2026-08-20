package org.tornotron.echno_backend.finance.construction;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Control-account codes used when posting the journal entry for an approved
 * construction invoice. Tunable per environment via {@code finance.construction.*}
 * without code changes, mirroring {@link org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties}.
 *
 * <p>All must reference postable (leaf) accounts in the chart of accounts:
 * <ul>
 *   <li>{@code apAccountCode} - Accounts Payable, credited for the gross total on a
 *       purchase or expense invoice.</li>
 *   <li>{@code gstInputCode} - GST Input Credit, debited for the tax on a purchase or
 *       expense invoice.</li>
 *   <li>{@code defaultExpenseCode} - the default expense account debited for the net
 *       on a purchase or expense invoice.</li>
 *   <li>{@code defaultRevenueCode} - the default revenue account credited for the net
 *       on a sales or service invoice.</li>
 * </ul>
 * Posting is invoice-level to these default accounts; per-line accounts are a later
 * budgeting phase. The receivable-side codes (AR 1200, GST output 2210) are read from
 * {@code InvoicePostingProperties}, so they stay in one place.
 */
@Data
@Component
@ConfigurationProperties(prefix = "finance.construction")
public class ConstructionPostingProperties {

    /** Accounts Payable control account (postable leaf). */
    private String apAccountCode = "2100";

    /** GST Input Credit control account (postable leaf). */
    private String gstInputCode = "1410";

    /** Default expense account for purchase/expense invoices (postable leaf). */
    private String defaultExpenseCode = "5100";

    /** Default revenue account for sales/service invoices (postable leaf). */
    private String defaultRevenueCode = "4100";
}
