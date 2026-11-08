package org.tornotron.echno_backend.modules.documentingestion.domain;

/** How a value was found. There is no confidence score; flags say what a reader should check. */
public enum ExtractionMethod {
    DETERMINISTIC,
    LLM,
    OCR_LLM
}
