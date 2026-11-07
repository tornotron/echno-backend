package org.tornotron.echno_backend.modules.documentingestion.domain;

/** What a candidate proposes. One extractor and one applier per kind; the payload shape is the kind's. */
public enum CandidateKind {
    BOQ_ITEM,
    PROJECT_FIELD,
    MILESTONE,
    CONTRACT_TERM,
    OBLIGATION,
    QUALITY_REQUIREMENT,
    DRAWING,
    QUALIFICATION_REQUIREMENT,
    CHANGE
}
