package org.tornotron.echno_backend.project.spatial;

/**
 * The four levels of a project's site structure, top down. The chain is strict: a node's
 * parent must sit exactly one level above it, so every element path has the same depth and
 * a path-prefix query on any node returns its whole subtree.
 */
public enum SpatialLevel {
    BUILDING(0),
    FLOOR(1),
    ZONE(2),
    ELEMENT(3);

    private final int depth;

    SpatialLevel(int depth) {
        this.depth = depth;
    }

    public int depth() {
        return depth;
    }

    /** The level a parent of this level must have, or {@code null} for a building. */
    public SpatialLevel parentLevel() {
        return depth == 0 ? null : values()[depth - 1];
    }

    /** The level a child of this level must have, or {@code null} for an element. */
    public SpatialLevel childLevel() {
        return depth == values().length - 1 ? null : values()[depth + 1];
    }
}
