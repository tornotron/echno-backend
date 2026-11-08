package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * Where a segment is. A proposed segment waits for a person; extraction of a segment starts only once
 * it is {@code CONFIRMED}. {@code PARTIAL} means some pages could not be read, and the segment names
 * them. A segment with no status predates classification and was confirmed when it was made (a BOQ
 * sheet confirmed through its column mapping).
 */
public enum SegmentStatus {
    PROPOSED,
    CONFIRMED,
    EXTRACTING,
    EXTRACTED,
    PARTIAL,
    FAILED
}
