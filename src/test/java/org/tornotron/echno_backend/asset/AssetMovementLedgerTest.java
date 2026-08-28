package org.tornotron.echno_backend.asset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.asset.dto.AssetCreationDto;
import org.tornotron.echno_backend.asset.dto.AssetMovementCreationDto;
import org.tornotron.echno_backend.asset.mapper.AssetMapper;
import org.tornotron.echno_backend.asset.mapper.AssetMovementMapper;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the asset movement ledger.
 *
 * <p>What is worth pinning here is not that rows get written but that they cannot be avoided:
 * the asset's project, location and custodian are a cache of the latest ledger entry rather than
 * a second source of truth, so every path that changes them has to append an entry, an entry has
 * to say why, and an entry once written is superseded rather than edited. Plain Mockito with no
 * Spring context, so nothing here adds a cached application context to the test JVM.
 */
@ExtendWith(MockitoExtension.class)
class AssetMovementLedgerTest {

    private static final Long ORG = 100L;
    private static final Long ASSET = 12L;
    private static final Long PROJECT_A = 3L;
    private static final Long PROJECT_B = 5L;
    private static final Long LOCATION = 7L;
    private static final Long USER = 4L;

    @Mock private AssetRepository assetRepository;
    @Mock private AssetMapper assetMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private VendorRepository vendorRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AssetMovementRepository assetMovementRepository;
    @Mock private AssetMovementMapper assetMovementMapper;
    @Mock private UserContextService userContextService;
    @Mock private AttachmentService attachmentService;

    private AssetService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);

        service = new AssetService(assetRepository, assetMapper, tenantEntityHelper, vendorRepository,
                storageLocationRepository, projectRepository, assetMovementRepository,
                assetMovementMapper, userContextService, attachmentService);

        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        lenient().when(assetRepository.save(any(Asset.class))).thenAnswer(inv -> {
            Asset asset = inv.getArgument(0);
            if (asset.getId() == null) {
                asset.setId(ASSET);
            }
            return asset;
        });
        lenient().when(assetMovementRepository.save(any(AssetMovement.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userContextService.getCurrentUserId()).thenReturn(USER);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Project project(Long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setProjectName(name);
        return project;
    }

    private StorageLocation location(Long id, String name) {
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(id);
        storageLocation.setLocationName(name);
        return storageLocation;
    }

    private Asset existingAsset() {
        Asset asset = new Asset();
        asset.setId(ASSET);
        asset.setName("JCB 3DX Backhoe Loader");
        asset.setOrganization(organization);
        return asset;
    }

    private AssetCreationDto creationDto() {
        AssetCreationDto dto = new AssetCreationDto();
        dto.setName("JCB 3DX Backhoe Loader");
        return dto;
    }

    private AssetMovementCreationDto movementDto(Long toProjectId, String reason) {
        AssetMovementCreationDto dto = new AssetMovementCreationDto();
        dto.setToProjectId(toProjectId);
        dto.setReason(reason);
        return dto;
    }

    private AssetMovement captureMovement() {
        ArgumentCaptor<AssetMovement> captor = ArgumentCaptor.forClass(AssetMovement.class);
        verify(assetMovementRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void create_opensTheLedgerWithARegistrationEntry() {
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_A, ORG))
                .thenReturn(Optional.of(project(PROJECT_A, "Central Yard")));
        AssetCreationDto dto = creationDto();
        dto.setAssignedProjectId(PROJECT_A);

        service.createAsset(dto);

        AssetMovement movement = captureMovement();
        assertThat(movement.getMovementType()).isEqualTo(AssetMovementType.REGISTRATION);
        assertThat(movement.getToProject().getId()).isEqualTo(PROJECT_A);
        assertThat(movement.getFromProject()).isNull();
        assertThat(movement.getReason()).isEqualTo(AssetService.REGISTRATION_REASON);
        assertThat(movement.getMovedBy()).isEqualTo(USER);
    }

    @Test
    void update_thatMovesTheAsset_appendsATransferAndBringsTheAssetWithIt() {
        Asset asset = existingAsset();
        asset.setAssignedProject(project(PROJECT_A, "Central Yard"));
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_B, ORG))
                .thenReturn(Optional.of(project(PROJECT_B, "Silver Oak Residences")));

        AssetCreationDto dto = creationDto();
        dto.setAssignedProjectId(PROJECT_B);

        service.updateAsset(ASSET, dto);

        AssetMovement movement = captureMovement();
        assertThat(movement.getMovementType()).isEqualTo(AssetMovementType.TRANSFER);
        assertThat(movement.getFromProject().getId()).isEqualTo(PROJECT_A);
        assertThat(movement.getToProject().getId()).isEqualTo(PROJECT_B);
        assertThat(movement.getReason()).isEqualTo(AssetService.EDIT_REASON);
        // The asset follows the entry rather than being set independently of it.
        assertThat(asset.getAssignedProject().getId()).isEqualTo(PROJECT_B);
    }

    @Test
    void update_thatMovesNothing_appendsNoEntry() {
        Asset asset = existingAsset();
        asset.setAssignedProject(project(PROJECT_A, "Central Yard"));
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_A, ORG))
                .thenReturn(Optional.of(project(PROJECT_A, "Central Yard")));

        AssetCreationDto dto = creationDto();
        dto.setAssignedProjectId(PROJECT_A);
        dto.setSerialNumber("JCB3DX2023-0456");

        service.updateAsset(ASSET, dto);

        verify(assetMovementRepository, never()).save(any(AssetMovement.class));
    }

    @Test
    void update_fromAClientStillSendingTheProjectName_resolvesItRatherThanUnassigningTheAsset() {
        Asset asset = existingAsset();
        Project central = project(PROJECT_A, "Central Yard");
        asset.setAssignedProject(central);
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));

        AssetCreationDto dto = creationDto();
        dto.setAssignedProject("Silver Oak Residences");
        when(projectRepository.findByNormalisedName(ORG, "silver oak residences"))
                .thenReturn(List.of(project(PROJECT_B, "Silver Oak Residences")));

        service.updateAsset(ASSET, dto);

        assertThat(captureMovement().getToProject().getId()).isEqualTo(PROJECT_B);
        assertThat(asset.getAssignedProject().getId()).isEqualTo(PROJECT_B);
    }

    @Test
    void update_thatDidNotTouchTheProjectName_leavesTheAssetWhereItIs() {
        Asset asset = existingAsset();
        asset.setAssignedProject(project(PROJECT_A, "Central Yard"));
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));

        AssetCreationDto dto = creationDto();
        dto.setAssignedProject("Central Yard");
        dto.setSerialNumber("JCB3DX2023-0456");

        service.updateAsset(ASSET, dto);

        verify(assetMovementRepository, never()).save(any(AssetMovement.class));
        assertThat(asset.getAssignedProject().getId()).isEqualTo(PROJECT_A);
    }

    @Test
    void update_resendingTextThatNeverResolved_leavesTheAssetWhereItIs() {
        Asset asset = existingAsset();
        asset.setLegacyAssignedProject("Marina Hts - phase 2 (old sheet)");
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));

        AssetCreationDto dto = creationDto();
        dto.setAssignedProject("Marina Hts - phase 2 (old sheet)");

        service.updateAsset(ASSET, dto);

        verify(assetMovementRepository, never()).save(any(AssetMovement.class));
        assertThat(asset.getAssignedProject()).isNull();
    }

    @Test
    void update_withAChangedProjectNameThatResolvesToNothing_isRefusedRatherThanClearing() {
        Asset asset = existingAsset();
        Project central = project(PROJECT_A, "Central Yard");
        asset.setAssignedProject(central);
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByNormalisedName(ORG, "somewhere that does not exist"))
                .thenReturn(List.of());

        AssetCreationDto dto = creationDto();
        dto.setAssignedProject("Somewhere that does not exist");

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.updateAsset(ASSET, dto))
                .withMessageContaining("Send assignedProjectId instead");
        assertThat(asset.getAssignedProject()).isSameAs(central);
    }

    @Test
    void update_withAnAmbiguousProjectName_isRefusedRatherThanGuessing() {
        Asset asset = existingAsset();
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByNormalisedName(ORG, "phase 1"))
                .thenReturn(List.of(project(PROJECT_A, "Phase 1"), project(PROJECT_B, "Phase 1")));

        AssetCreationDto dto = creationDto();
        dto.setAssignedProject("Phase 1");

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.updateAsset(ASSET, dto))
                .withMessageContaining("names 2 projects");
    }

    @Test
    void recordMovement_carriesTheReasonTheCallerGave() {
        Asset asset = existingAsset();
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_B, ORG))
                .thenReturn(Optional.of(project(PROJECT_B, "Silver Oak Residences")));

        service.recordMovement(ASSET, movementDto(PROJECT_B, "Mobilised for the piling phase"));

        assertThat(captureMovement().getReason()).isEqualTo("Mobilised for the piling phase");
    }

    @Test
    void recordMovement_snapshotsTheProjectNameSoARenameCannotRewriteHistory() {
        Asset asset = existingAsset();
        Project from = project(PROJECT_A, "Central Yard");
        asset.setAssignedProject(from);
        asset.setLocation(location(LOCATION, "Kochi Yard"));
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_B, ORG))
                .thenReturn(Optional.of(project(PROJECT_B, "Silver Oak Residences")));

        service.recordMovement(ASSET, movementDto(PROJECT_B, "Mobilised for the piling phase"));

        AssetMovement movement = captureMovement();
        assertThat(movement.getFromProjectName()).isEqualTo("Central Yard");
        assertThat(movement.getToProjectName()).isEqualTo("Silver Oak Residences");
        assertThat(movement.getFromLocationName()).isEqualTo("Kochi Yard");

        // The project is renamed afterwards; the entry still reads as it did at the time.
        from.setProjectName("Central Yard (closed)");
        assertThat(movement.getFromProjectName()).isEqualTo("Central Yard");
    }

    @Test
    void recordMovement_keepsTheFreeTextOfAnAssetThatNeverMatchedAProject() {
        Asset asset = existingAsset();
        asset.setLegacyAssignedProject("Marina Heights - phase 2 (old sheet)");
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_B, ORG))
                .thenReturn(Optional.of(project(PROJECT_B, "Silver Oak Residences")));

        service.recordMovement(ASSET, movementDto(PROJECT_B, "Mobilised for the piling phase"));

        assertThat(captureMovement().getFromProjectName())
                .isEqualTo("Marina Heights - phase 2 (old sheet)");
    }

    @Test
    void recordMovement_withNoStatedReason_isRefused() {
        Asset asset = existingAsset();
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_B, ORG))
                .thenReturn(Optional.of(project(PROJECT_B, "Silver Oak Residences")));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.recordMovement(ASSET, movementDto(PROJECT_B, "   ")))
                .withMessageContaining("must say why it happened");
        verify(assetMovementRepository, never()).save(any(AssetMovement.class));
    }

    @Test
    void recordMovement_thatMovesNothing_isRefused() {
        Asset asset = existingAsset();
        asset.setAssignedProject(project(PROJECT_A, "Central Yard"));
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_A, ORG))
                .thenReturn(Optional.of(project(PROJECT_A, "Central Yard")));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.recordMovement(ASSET, movementDto(PROJECT_A, "Nothing changed")))
                .withMessageContaining("no movement to record");
        verify(assetMovementRepository, never()).save(any(AssetMovement.class));
    }

    @Test
    void recordMovement_datedInTheFuture_isRefused() {
        Asset asset = existingAsset();
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_B, ORG))
                .thenReturn(Optional.of(project(PROJECT_B, "Silver Oak Residences")));

        AssetMovementCreationDto dto = movementDto(PROJECT_B, "Planned mobilisation");
        dto.setMovedAt(LocalDateTime.now().plusDays(3));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.recordMovement(ASSET, dto))
                .withMessageContaining("in the future");
        verify(assetMovementRepository, never()).save(any(AssetMovement.class));
    }

    @Test
    void recordMovement_correctingAnEarlierEntry_appendsACorrectionRatherThanEditingIt() {
        Asset asset = existingAsset();
        Project onSite = project(PROJECT_A, "Central Yard");
        asset.setAssignedProject(onSite);
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_A, ORG)).thenReturn(Optional.of(onSite));

        AssetMovement wrong = new AssetMovement();
        wrong.setId(409L);
        wrong.setAsset(asset);
        when(assetMovementRepository.findByIdAndOrganization_Id(409L, ORG)).thenReturn(Optional.of(wrong));

        AssetMovementCreationDto dto = movementDto(PROJECT_A, "The 12 Aug transfer named the wrong yard");
        dto.setCorrectsMovementId(409L);

        service.recordMovement(ASSET, dto);

        AssetMovement movement = captureMovement();
        // A correction is a new entry even though it moves the asset nowhere.
        assertThat(movement.getMovementType()).isEqualTo(AssetMovementType.CORRECTION);
        assertThat(movement.getCorrectsMovementId()).isEqualTo(409L);
        // The wrong entry is untouched: it is superseded, not edited.
        assertThat(movement).isNotSameAs(wrong);
        assertThat(wrong.getCorrectsMovementId()).isNull();
        assertThat(wrong.getMovementType()).isNull();
    }

    @Test
    void recordMovement_correctingAnEntryOfAnotherAsset_isRefused() {
        Asset asset = existingAsset();
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));

        Asset other = new Asset();
        other.setId(99L);
        AssetMovement elsewhere = new AssetMovement();
        elsewhere.setId(409L);
        elsewhere.setAsset(other);
        when(assetMovementRepository.findByIdAndOrganization_Id(409L, ORG)).thenReturn(Optional.of(elsewhere));

        AssetMovementCreationDto dto = movementDto(PROJECT_B, "Restating an entry");
        dto.setCorrectsMovementId(409L);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.recordMovement(ASSET, dto))
                .withMessageContaining("belongs to a different asset");
    }

    @Test
    void delete_isRefusedOnceTheAssetHasMoved() {
        Asset asset = existingAsset();
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(assetMovementRepository.countByAsset_IdAndOrganization_Id(ASSET, ORG)).thenReturn(3L);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.deleteAsset(ASSET))
                .withMessageContaining("3 recorded movements");
        verify(assetRepository, never()).delete(any(Asset.class));
    }

    @Test
    void delete_isAllowedForAnAssetThatHasNoHistory() {
        Asset asset = existingAsset();
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(assetMovementRepository.countByAsset_IdAndOrganization_Id(ASSET, ORG)).thenReturn(0L);

        service.deleteAsset(ASSET);

        verify(assetRepository).delete(asset);
    }

    @Test
    void recordMovement_toAProjectOfAnotherOrganization_isRefused() {
        Asset asset = existingAsset();
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_B, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.recordMovement(ASSET, movementDto(PROJECT_B, "Mobilised")))
                .withMessageContaining("Project with ID " + PROJECT_B);
        verify(assetMovementRepository, never()).save(any(AssetMovement.class));
    }
}
