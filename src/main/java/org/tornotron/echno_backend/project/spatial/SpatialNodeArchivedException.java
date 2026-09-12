package org.tornotron.echno_backend.project.spatial;

/** The target of a write is an archived node. Maps to 422. */
public class SpatialNodeArchivedException extends RuntimeException {
    public SpatialNodeArchivedException(String message) {
        super(message);
    }
}
