package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * Where a parse job is. The worker owns QUEUED to RUNNING and RUNNING to DONE or FAILED; the backend
 * poller owns the recoveries. DONE and FAILED are terminal, and a retry is a new row. Contract:
 * {@code docs/specs/2026-11-08-ingest-worker-contract.md}.
 */
public enum ParseJobStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED
}
