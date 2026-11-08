package org.tornotron.echno_backend.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.asset.dto.AssetMovementCreationDto;
import org.tornotron.echno_backend.asset.mapper.AssetMapper;
import org.tornotron.echno_backend.asset.mapper.AssetMovementMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.vendor.VendorRepository;

/**
 * The register asks the access matrix about each asset, not only about the area: a caller whose grants
 * reach one project sees, changes and moves only that project's assets.
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;

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
    @Mock private SiteTransferItemRepository siteTransferItemRepository;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProjectAccess access = new OneProjectAccess();
    private AssetService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        Employee caller = new Employee();
        caller.setId(7L);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        service = new AssetService(assetRepository, assetMapper, tenantEntityHelper, vendorRepository,
                storageLocationRepository, projectRepository, assetMovementRepository,
                assetMovementMapper, userContextService, attachmentService, siteTransferItemRepository,
                new AssetAccess(access, currentEmployee));
        lenient().when(assetRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG))
                .thenReturn(Optional.of(asset(ON_MY_PROJECT, MY_PROJECT)));
        lenient().when(assetRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG))
                .thenReturn(Optional.of(asset(ELSEWHERE, OTHER_PROJECT)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anAssetTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getAssetById(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getDocuments(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getMovements(ELSEWHERE, 0, 20));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getPlacementHistory(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.deleteAsset(ELSEWHERE));
    }

    @Test
    void anAssetTheCallerMaySeeButNotDeleteIsRefusedAndKept() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.deleteAsset(ON_MY_PROJECT));
        verify(assetRepository, never()).delete(any(Asset.class));
    }

    @Test
    void anAssetCannotBeMovedOutOfWhatTheCallerMayMove() {
        Project other = new Project();
        other.setId(OTHER_PROJECT);
        when(projectRepository.findByIdAndOrganization_Id(OTHER_PROJECT, ORG)).thenReturn(Optional.of(other));
        lenient().when(assetMovementRepository.save(any(AssetMovement.class))).thenAnswer(inv -> inv.getArgument(0));
        AssetMovementCreationDto move = new AssetMovementCreationDto();
        move.setToProjectId(OTHER_PROJECT);
        move.setReason("Sent to the other site");

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.recordMovement(ON_MY_PROJECT, move));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void theListIsNarrowedInTheQuery() {
        when(assetRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.getAllAssets(0, 10);

        verify(assetRepository).findAll(any(Specification.class), any(Pageable.class));
        verify(assetRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void aCallerWhoMaySeeEveryAssetGetsTheUnnarrowedList() {
        access.everything = true;
        when(assetRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.getAllAssets(0, 10);

        verify(assetRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aCallerWhoMaySeeNoAssetIsNotQueriedFor() {
        access.nothing = true;

        assertThat(service.getAllAssets(0, 10)).isEmpty();
        assertThat(service.getExpiringDocuments(30)).isEmpty();
        verify(assetRepository, never()).findAll(any(Pageable.class));
        verify(attachmentService, never()).getExpiringDocuments(anyString(), any());
    }

    @Test
    void expiringDocumentsAreNarrowedToTheAssetsTheCallerMaySee() {
        service.getExpiringDocuments(30);

        verify(attachmentService).getExpiringDocuments(eq(AssetDocuments.ENTITY_TYPE), any(LocalDate.class),
                any(Specification.class));
        verify(attachmentService, never()).getExpiringDocuments(anyString(), any());
    }

    private Asset asset(Long id, Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        Asset asset = new Asset();
        asset.setId(id);
        asset.setName("Asset " + id);
        asset.setOrganization(organization);
        asset.setAssignedProject(project);
        return asset;
    }

    /** Grants read and create on one project's assets, and nothing else. */
    private static final class OneProjectAccess implements AccessService {

        private static final Set<String> HELD = Set.of(AccessPermission.READ, AccessPermission.CREATE);

        boolean everything;
        boolean nothing;

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return true;
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return !nothing && HELD.contains(permission.action());
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            if (nothing || !HELD.contains(permission.action())) {
                return AccessFilter.none(subject.organizationId());
            }
            if (everything) {
                return AccessFilter.all(subject.organizationId());
            }
            return new AccessFilter(subject.organizationId(), false, Set.of(MY_PROJECT), null, null);
        }

        @Override
        public AccessGrants grantsFor(AccessSubject subject) {
            return AccessGrants.none();
        }
    }
}
