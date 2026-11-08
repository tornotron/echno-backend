package org.tornotron.echno_backend.modules.documentingestion.domain;

/** How a document's class or a segment was proposed: from the file's name, type and headings, by the LLM, or by a person. */
public enum ClassMethod {
    DETERMINISTIC,
    LLM,
    PERSON
}
