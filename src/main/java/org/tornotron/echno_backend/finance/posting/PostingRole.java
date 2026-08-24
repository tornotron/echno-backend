package org.tornotron.echno_backend.finance.posting;

/**
 * The fixed set of control-account roles the finance module posts to.
 *
 * <p>Each role names a slot in the double-entry postings raised by the invoice, construction
 * invoice and payment services. A role is resolved to a concrete {@code Account} per tenant: an
 * organization may map a role to any postable leaf account it likes, and where it has set no
 * mapping the module falls back to the code configured on the posting properties. Introducing this
 * indirection lets a tenant point, for example, its default expense postings at a different account
 * without editing configuration or touching the seeded chart.
 */
public enum PostingRole {

    /** Accounts Receivable, debited for the gross total on a sales or service invoice. */
    ACCOUNTS_RECEIVABLE,

    /** GST Output Payable, credited for the tax on a sales or service invoice. */
    GST_OUTPUT,

    /** Accounts Payable, credited for the gross total on a purchase or expense invoice. */
    ACCOUNTS_PAYABLE,

    /** GST Input Credit, debited for the tax on a purchase or expense invoice. */
    GST_INPUT,

    /** Default revenue account, credited for the net on a sales or service invoice. */
    DEFAULT_REVENUE,

    /** Default expense account, debited for the net on a purchase or expense invoice. */
    DEFAULT_EXPENSE
}
