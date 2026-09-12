package org.tornotron.echno_backend.modules.bim;

import java.util.UUID;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;

/**
 * The object-store layout per model version, shared with the worker by the import
 * contract. Everything for one version sits under one prefix so a version can be removed
 * with one prefix delete and a tile URL can be checked against its version by prefix.
 */
public final class BimStorageLayout {

    public static final String ENTITY_TYPE = "BIM_MODEL";
    public static final String SOURCE_FILE = "source.ifc";
    public static final String ELEMENTS_FILE = "elements.jsonl";
    public static final String STRUCTURE_FILE = "structure.json";
    public static final String META_FILE = "model-meta.json";
    public static final String COARSE_TILE = "tiles/coarse.glb";

    private BimStorageLayout() {
    }

    public static AttachmentOwner ownerOf(UUID modelId) {
        return AttachmentOwner.of(ENTITY_TYPE, modelId);
    }

    public static String prefix(UUID modelId, UUID versionId) {
        return "bim/" + modelId + "/" + versionId + "/";
    }

    public static String sourceKey(UUID modelId, UUID versionId) {
        return prefix(modelId, versionId) + SOURCE_FILE;
    }

    public static String elementsKey(UUID modelId, UUID versionId) {
        return prefix(modelId, versionId) + ELEMENTS_FILE;
    }

    public static String structureKey(UUID modelId, UUID versionId) {
        return prefix(modelId, versionId) + STRUCTURE_FILE;
    }

    public static String metaKey(UUID modelId, UUID versionId) {
        return prefix(modelId, versionId) + META_FILE;
    }

    public static String coarseTileKey(UUID modelId, UUID versionId) {
        return prefix(modelId, versionId) + COARSE_TILE;
    }

    public static String storeyTileKey(UUID modelId, UUID versionId, String storeyGlobalId) {
        return prefix(modelId, versionId) + "tiles/" + storeyGlobalId + ".glb";
    }
}
