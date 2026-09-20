package org.tornotron.echno_backend.documentReversal.enums;

/**
 * Where a reversal request stands.
 *
 * <p>{@link #PENDING} is the only state a request can be written in, and the only one it can
 * leave. The three ways out are terminal: an approver undoes the document ({@link #APPROVED}),
 * an approver refuses with a reason ({@link #REJECTED}), or the requester withdraws it
 * ({@link #CANCELLED}). A document carries at most one pending request at a time, which the
 * partial unique index on {@code document_reversal} enforces.
 */
public enum DocumentReversalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}
