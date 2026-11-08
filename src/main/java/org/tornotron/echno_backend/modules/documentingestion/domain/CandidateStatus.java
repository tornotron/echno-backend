package org.tornotron.echno_backend.modules.documentingestion.domain;

/** Where a candidate is in review. APPLIED and SUPERSEDED are final; APPLY_FAILED goes back to ACCEPTED when a person accepts it again. */
public enum CandidateStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED,
    APPLIED,
    APPLY_FAILED,
    SUPERSEDED
}
