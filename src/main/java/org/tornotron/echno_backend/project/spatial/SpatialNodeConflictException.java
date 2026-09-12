package org.tornotron.echno_backend.project.spatial;

/** A sibling code clash, a parent of the wrong level, or a move into a node's own subtree. Maps to 409. */
public class SpatialNodeConflictException extends RuntimeException {
    public SpatialNodeConflictException(String message) {
        super(message);
    }
}
