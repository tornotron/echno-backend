package org.tornotron.echno_backend.modules.bim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimSourceUploadDto;
import org.tornotron.echno_backend.modules.bim.dto.BimTileManifestDto;
import org.tornotron.echno_backend.modules.bim.dto.PresignBimSourceRequest;
import org.tornotron.echno_backend.modules.bim.mapper.BimMapperImpl;
import org.tornotron.echno_backend.modules.bim.repository.BimModelVersionRepository;
import org.tornotron.echno_backend.modules.bim.service.BimModelService;
import org.tornotron.echno_backend.modules.bim.service.BimTileService;
import org.tornotron.echno_backend.modules.bim.service.BimUploadService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The upload goes through the attachment path to a key fixed by the layout, the cap refuses
 * oversize files, and every tile URL is signed for a key under the version's own prefix for a
 * version the caller's tenant can read.
 */
class BimUploadAndTilesTest {

    private final BimModelService models = mock(BimModelService.class);
    private final BimModelVersionRepository versions = mock(BimModelVersionRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final UserContextService users = mock(UserContextService.class);
    private final BimUploadService upload = new BimUploadService(models, versions, storage, attachments, users,
            new BimMapperImpl());
    private final BimTileService tiles = new BimTileService(models, storage);

    private final UUID modelId = UUID.randomUUID();
    private final BimModel model = new BimModel();

    @BeforeEach
    void model() {
        ReflectionTestUtils.setField(upload, "maxSourceBytes", 1024L * 1024 * 1024);
        model.setId(modelId);
        model.setProjectId(5L);
        model.setOrganization(new Organization());
        when(models.requireModel(modelId)).thenReturn(model);
        when(versions.maxVersionNumber(modelId)).thenReturn(2);
        when(versions.saveAndFlush(any())).thenAnswer(inv -> {
            BimModelVersion v = inv.getArgument(0);
            v.setId(UUID.randomUUID());
            return v;
        });
        when(storage.generateUploadUrlForKey(anyString(), anyString(), any()))
                .thenAnswer(inv -> new PresignedUpload(inv.getArgument(0), "https://store/" + inv.getArgument(0),
                        inv.getArgument(1), 3600));
        when(storage.generateDownloadUrl(anyString(), any())).thenAnswer(inv -> "https://store/" + inv.getArgument(0));
    }

    @Test
    void presignCreatesTheNextVersionAtTheLayoutsKeyThroughAnExactKeyPut() {
        BimSourceUploadDto out = upload.presignSource(modelId,
                new PresignBimSourceRequest("Tower-A.ifc", null, 200L * 1024 * 1024));

        assertThat(out.versionNumber()).isEqualTo(3);
        assertThat(out.upload().key()).isEqualTo(BimStorageLayout.sourceKey(modelId, out.versionId()));
        assertThat(out.upload().contentType()).isEqualTo("application/x-step");
        verify(storage).generateUploadUrlForKey(eq(out.upload().key()), eq("application/x-step"), any(Duration.class));
        verify(storage, never()).generateUploadUrl(anyString(), anyString(), anyString(), any());
    }

    @Test
    void oversizeAndNonIfcFilesAreRefusedBeforeAnythingIsCreated() {
        assertThatThrownBy(() -> upload.presignSource(modelId,
                new PresignBimSourceRequest("Tower-A.ifc", null, 1024L * 1024 * 1024 + 1)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Split the model by building");
        assertThatThrownBy(() -> upload.presignSource(modelId, new PresignBimSourceRequest("Tower-A.rvt", null, 10L)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("IFC");
        verify(versions, never()).saveAndFlush(any());
    }

    @Test
    void registerVerifiesTheObjectFilesItAgainstTheModelAndQueuesTheWorker() {
        UUID versionId = UUID.randomUUID();
        BimModelVersion version = uploaded(versionId);
        when(models.requireVersion(modelId, versionId)).thenReturn(version);
        when(storage.objectExists(version.getSourceKey())).thenReturn(false);

        assertThatThrownBy(() -> upload.registerSource(modelId, versionId))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("upload may not have completed");
        verify(models, never()).enqueueImport(any(), any());

        when(storage.objectExists(version.getSourceKey())).thenReturn(true);
        upload.registerSource(modelId, versionId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegisterUploadRequest>> filed = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<AttachmentOwner> owner = ArgumentCaptor.forClass(AttachmentOwner.class);
        verify(attachments).registerUploads(filed.capture(), owner.capture(), eq("bim"));
        assertThat(owner.getValue().entityType()).isEqualTo("BIM_MODEL");
        assertThat(owner.getValue().entityUuid()).isEqualTo(modelId);
        assertThat(filed.getValue()).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo(BimStorageLayout.sourceKey(modelId, versionId));
            assertThat(r.filename()).isEqualTo("Tower-A.ifc");
        });
        verify(models).enqueueImport(modelId, versionId);
    }

    @Test
    void tileUrlsAreSignedOnlyForKeysUnderTheVersionsPrefix() {
        UUID versionId = UUID.randomUUID();
        BimModelVersion version = ready(versionId, Map.of(
                "coarseTile", "tiles/coarse.glb",
                "unassignedTile", "tiles/unassigned.glb",
                "storeys", List.of(
                        Map.of("globalId", "S1", "name", "Level 01", "elevation", 0.0, "elementCount", 12, "tile", "tiles/S1.glb"),
                        Map.of("globalId", "S2", "name", "Level 02", "elevation", 3.0, "elementCount", 9))));
        when(models.requireVersion(modelId, versionId)).thenReturn(version);

        BimTileManifestDto manifest = tiles.manifest(modelId, versionId);

        String prefix = BimStorageLayout.prefix(modelId, versionId);
        assertThat(manifest.expiresInSeconds()).isEqualTo(900);
        assertThat(manifest.coarseUrl()).isEqualTo("https://store/" + prefix + "tiles/coarse.glb");
        assertThat(manifest.unassignedUrl()).isEqualTo("https://store/" + prefix + "tiles/unassigned.glb");
        assertThat(manifest.storeys()).extracting(BimTileManifestDto.StoreyTile::url).containsExactly(
                "https://store/" + prefix + "tiles/S1.glb", "https://store/" + prefix + "tiles/S2.glb");
        assertThat(manifest.storeys().get(0).elementCount()).isEqualTo(12);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(storage, org.mockito.Mockito.times(4)).generateDownloadUrl(keys.capture(), eq(Duration.ofMinutes(15)));
        assertThat(keys.getAllValues()).allSatisfy(k -> assertThat(k).startsWith(prefix + "tiles/"));
    }

    @Test
    void aTilePathLeavingThePrefixAndANotReadyVersionAreRefusedAndAStrangerSeesNothing() {
        UUID versionId = UUID.randomUUID();
        when(models.requireVersion(modelId, versionId)).thenReturn(ready(versionId,
                Map.of("coarseTile", "../" + UUID.randomUUID() + "/tiles/coarse.glb")));
        assertThatThrownBy(() -> tiles.manifest(modelId, versionId))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not inside the version's tiles folder");
        verify(storage, never()).generateDownloadUrl(anyString(), any());

        UUID pending = UUID.randomUUID();
        when(models.requireVersion(modelId, pending)).thenReturn(uploaded(pending));
        assertThatThrownBy(() -> tiles.manifest(modelId, pending))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("READY");

        UUID foreign = UUID.randomUUID();
        when(models.requireModel(foreign)).thenThrow(new ResourceNotFoundException("BIM model not found: " + foreign));
        assertThatThrownBy(() -> tiles.manifest(foreign, versionId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> upload.presignSource(foreign, new PresignBimSourceRequest("a.ifc", null, 10L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private BimModelVersion uploaded(UUID versionId) {
        BimModelVersion v = new BimModelVersion();
        v.setId(versionId);
        v.setModelId(modelId);
        v.setVersionNumber(3);
        v.setSourceFilename("Tower-A.ifc");
        v.setSourceSizeBytes(1024L);
        v.setSourceKey(BimStorageLayout.sourceKey(modelId, versionId));
        v.setStatus(BimVersionStatus.UPLOADED);
        return v;
    }

    private BimModelVersion ready(UUID versionId, Map<String, Object> meta) {
        BimModelVersion v = uploaded(versionId);
        v.setStatus(BimVersionStatus.READY);
        v.setMeta(meta);
        return v;
    }
}
