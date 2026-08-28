package org.tornotron.echno_backend.asset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.asset.dto.AssetPlacementSpanDto;
import org.tornotron.echno_backend.asset.mapper.AssetMapper;
import org.tornotron.echno_backend.asset.mapper.AssetMovementMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the placement history the ledger is read into: "14 days at Central Yard,
 * 45 days at Silver Oak". The durations are worked out from consecutive entries on every read
 * rather than stored anywhere, which is what stops them drifting from the ledger, so what needs
 * pinning is that consecutive entries close each other and only the last stretch stays open.
 */
@ExtendWith(MockitoExtension.class)
class AssetPlacementHistoryTest {

    private static final Long ORG = 100L;
    private static final Long ASSET = 12L;

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

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new AssetService(assetRepository, assetMapper, tenantEntityHelper, vendorRepository,
                storageLocationRepository, projectRepository, assetMovementRepository,
                assetMovementMapper, userContextService, attachmentService);

        Organization organization = new Organization();
        organization.setId(ORG);
        Asset asset = new Asset();
        asset.setId(ASSET);
        asset.setOrganization(organization);
        when(assetRepository.findByIdAndOrganization_Id(ASSET, ORG)).thenReturn(Optional.of(asset));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AssetMovement entry(Long id, String projectName, LocalDateTime movedAt, String reason) {
        AssetMovement movement = new AssetMovement();
        movement.setId(id);
        movement.setToProjectName(projectName);
        movement.setMovedAt(movedAt);
        movement.setReason(reason);
        return movement;
    }

    @Test
    void eachEntryIsClosedByTheNextOne_andOnlyTheLastStretchIsStillOpen() {
        LocalDateTime now = LocalDateTime.now();
        List<AssetMovement> ledger = List.of(
                entry(1L, "Central Yard", now.minusDays(59), "Registered"),
                entry(2L, "Silver Oak Residences", now.minusDays(45), "Mobilised for the piling phase"),
                entry(3L, "Marina Heights Towers", now.minusDays(10), "Moved to the tower crane base"));
        // The repository answers newest first; the service turns it back into reading order.
        stubLedger(List.of(ledger.get(2), ledger.get(1), ledger.get(0)), 3L);

        List<AssetPlacementSpanDto> spans = service.getPlacementHistory(ASSET).getContent();

        assertThat(spans).hasSize(3);
        assertThat(spans).extracting(AssetPlacementSpanDto::getProjectName)
                .containsExactly("Central Yard", "Silver Oak Residences", "Marina Heights Towers");
        assertThat(spans).extracting(AssetPlacementSpanDto::getDays)
                .containsExactly(14L, 35L, 10L);
        assertThat(spans).extracting(AssetPlacementSpanDto::isCurrent)
                .containsExactly(false, false, true);
        assertThat(spans.get(0).getTo()).isEqualTo(ledger.get(1).getMovedAt());
        assertThat(spans.get(2).getTo()).isNull();
    }

    @Test
    void aSingleEntryIsOneOpenStretchCountedToNow() {
        LocalDateTime now = LocalDateTime.now();
        stubLedger(List.of(entry(1L, "Central Yard", now.minusDays(21), "Registered")), 1L);

        List<AssetPlacementSpanDto> spans = service.getPlacementHistory(ASSET).getContent();

        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getDays()).isEqualTo(21L);
        assertThat(spans.get(0).isCurrent()).isTrue();
        assertThat(spans.get(0).getReason()).isEqualTo("Registered");
    }

    @Test
    void anAssetWithNoLedgerHasNoHistoryRatherThanAnInventedOne() {
        stubLedger(List.of(), 0L);

        assertThat(service.getPlacementHistory(ASSET)).isEmpty();
    }

    @Test
    void aCappedReadKeepsTheNewestEntriesAndReportsTheWholeLedgerAsTheTotal() {
        LocalDateTime now = LocalDateTime.now();
        // Two entries returned out of eight hundred: the cap dropped the oldest, not the newest,
        // so the placement marked current is genuinely where the asset is.
        stubLedger(List.of(
                entry(800L, "Marina Heights Towers", now.minusDays(4), "Moved to the tower crane base"),
                entry(799L, "Silver Oak Residences", now.minusDays(30), "Mobilised for the piling phase")),
                800L);

        Page<AssetPlacementSpanDto> history = service.getPlacementHistory(ASSET);

        assertThat(history.getContent()).extracting(AssetPlacementSpanDto::getProjectName)
                .containsExactly("Silver Oak Residences", "Marina Heights Towers");
        assertThat(history.getContent().get(1).isCurrent()).isTrue();
        assertThat(history.getTotalElements()).isEqualTo(800L);
    }

    private void stubLedger(List<AssetMovement> newestFirst, long total) {
        when(assetMovementRepository.findByAsset_IdAndOrganization_IdOrderByMovedAtDescIdDesc(
                eq(ASSET), eq(ORG), any(Pageable.class)))
                .thenReturn(new PageImpl<>(newestFirst, PageRequest.of(0, 500), total));
    }
}
