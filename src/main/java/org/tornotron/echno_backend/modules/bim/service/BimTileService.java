package org.tornotron.echno_backend.modules.bim.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.bim.BimStorageLayout;
import org.tornotron.echno_backend.modules.bim.BimVersionStatus;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimTileManifestDto;
import org.tornotron.echno_backend.modules.bim.dto.BimTileManifestDto.StoreyTile;

/**
 * Tiles are served by short-lived presigned GETs, one per storey, so the browser streams only
 * the floors it opens and the object store (with the CDN in front of it in production) does
 * the serving. Every URL is signed for a key under the version's own prefix and only for a
 * version the caller's tenant can read; a tile path from {@code model-meta.json} that tries to
 * leave the prefix is refused.
 */
@Service
@RequiredArgsConstructor
public class BimTileService {

    static final Duration TILE_URL_EXPIRY = Duration.ofMinutes(15);

    private final BimModelService modelService;
    private final FileStorageService fileStorageService;

    @Transactional(readOnly = true)
    public BimTileManifestDto manifest(UUID modelId, UUID versionId) {
        modelService.requireModel(modelId);
        BimModelVersion version = modelService.requireVersion(modelId, versionId);
        if (version.getStatus() != BimVersionStatus.READY) {
            throw new InvalidRequestException("Version " + version.getVersionNumber() + " is " + version.getStatus()
                    + "; tiles are available once it is READY");
        }
        Map<String, Object> meta = version.getMeta() == null ? Map.of() : version.getMeta();
        String prefix = BimStorageLayout.prefix(modelId, versionId);

        String coarse = sign(prefix, str(meta.getOrDefault("coarseTile", BimStorageLayout.COARSE_TILE)));
        String unassigned = meta.get("unassignedTile") == null ? null : sign(prefix, str(meta.get("unassignedTile")));
        List<StoreyTile> storeys = new ArrayList<>();
        for (Map<String, Object> storey : storeyList(meta.get("storeys"))) {
            String guid = str(storey.get("globalId"));
            String tile = storey.get("tile") == null ? "tiles/" + guid + ".glb" : str(storey.get("tile"));
            storeys.add(new StoreyTile(guid, str(storey.get("name")), dbl(storey.get("elevation")),
                    intOf(storey.get("elementCount")), sign(prefix, tile)));
        }
        return new BimTileManifestDto(modelId, versionId, TILE_URL_EXPIRY.toSeconds(), coarse, unassigned, storeys);
    }

    /** The key a tile path resolves to, checked to stay inside the version's prefix. */
    static String tileKey(String prefix, String tilePath) {
        if (tilePath == null || tilePath.isBlank() || tilePath.startsWith("/") || tilePath.contains("..")
                || tilePath.contains("\\") || !tilePath.startsWith("tiles/")) {
            throw new InvalidRequestException("Tile path '" + tilePath + "' is not inside the version's tiles folder");
        }
        return prefix + tilePath;
    }

    private String sign(String prefix, String tilePath) {
        return fileStorageService.generateDownloadUrl(tileKey(prefix, tilePath), TILE_URL_EXPIRY);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> storeyList(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer intOf(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }
}
