package org.tornotron.echno_backend.materialConsumption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionCreationDto;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionDto;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;
import org.tornotron.echno_backend.materialConsumption.mapper.MaterialConsumptionMapper;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.task.TaskRepository;

/**
 * Material consumption asks the access matrix about the consumption itself, not only about the area:
 * a caller whose grants reach one project sees and records only that project's consumption.
 */
@ExtendWith(MockitoExtension.class)
class MaterialConsumptionServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;
    private static final Long MATERIAL = 5L;
    private static final Long CREATOR = 7L;

    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private InventoryService inventoryService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MaterialConsumptionMapper materialConsumptionMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProjectAccess access = new OneProjectAccess();
    private MaterialConsumptionService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        Employee caller = new Employee();
        caller.setId(CREATOR);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        service = new MaterialConsumptionService(materialConsumptionRepository, materialRepository,
                inventoryService, eventPublisher, materialConsumptionMapper, tenantEntityHelper,
                employeeRepository, projectRepository, storageLocationRepository, taskRepository,
                new MaterialConsumptionAccess(access, currentEmployee));
        lenient().when(materialConsumptionRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG))
                .thenReturn(Optional.of(consumption(ON_MY_PROJECT, MY_PROJECT)));
        lenient().when(materialConsumptionRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG))
                .thenReturn(Optional.of(consumption(ELSEWHERE, OTHER_PROJECT)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aConsumptionTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getMaterialConsumptionById(ELSEWHERE));
    }

    @Test
    void aConsumptionTheCallerMaySeeIsReturned() {
        MaterialConsumptionDto mapped = new MaterialConsumptionDto();
        when(materialConsumptionMapper.toDto(any(MaterialConsumption.class))).thenReturn(mapped);

        assertThat(service.getMaterialConsumptionById(ON_MY_PROJECT)).isSameAs(mapped);
        verify(materialConsumptionMapper).toDto(any(MaterialConsumption.class));
    }

    @Test
    void consumptionCannotBeRecordedOnAProjectTheCallerMayNotRecordItOn() {
        lookups(OTHER_PROJECT);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.createMaterialConsumption(issue(OTHER_PROJECT)));
        verify(inventoryService, never()).validateSufficientUnlocatedStock(anyLong(), anyLong(), anyDouble());
        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void consumptionIsRecordedOnTheCallersProject() {
        lookups(MY_PROJECT);
        when(materialConsumptionRepository.save(any(MaterialConsumption.class))).thenAnswer(call -> call.getArgument(0));

        service.createMaterialConsumption(issue(MY_PROJECT));

        verify(materialConsumptionRepository).save(any(MaterialConsumption.class));
    }

    @Test
    void aCallerWhoMayReadButNotRecordIsRefused() {
        access.mayCreate = false;
        lookups(MY_PROJECT);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.createMaterialConsumption(issue(MY_PROJECT)));
        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListsAreNarrowedInTheQuery() {
        when(materialConsumptionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(materialConsumptionRepository.findAll(any(Specification.class))).thenReturn(List.of());
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 0, 0);

        service.getAllMaterialConsumptions(0, 10);
        service.getConsumptionsByMaterial(MATERIAL);
        service.getConsumptionsByMaterial(MATERIAL, 0, 10);
        service.getConsumptionsByType(MaterialConsumptionType.USED_FROM_STOCK);
        service.getConsumptionsByType(MaterialConsumptionType.USED_FROM_STOCK, 0, 10);
        service.getConsumptionsByDateRange(from, to);
        service.getConsumptionsByDateRange(from, to, 0, 10);
        service.getConsumptionsByTask(3L);
        service.getConsumptionsByTask(3L, 0, 10);

        verify(materialConsumptionRepository, never()).findAll(any(Pageable.class));
        verify(materialConsumptionRepository, never()).findByMaterialId(any());
        verify(materialConsumptionRepository, never()).findByMaterialId(any(), any());
        verify(materialConsumptionRepository, never()).findByConsumptionType(any());
        verify(materialConsumptionRepository, never()).findByConsumptionType(any(), any());
        verify(materialConsumptionRepository, never()).findByConsumptionDateBetween(any(), any());
        verify(materialConsumptionRepository, never()).findByConsumptionDateBetween(any(), any(), any());
        verify(materialConsumptionRepository, never()).findByTaskId(any());
        verify(materialConsumptionRepository, never()).findByTaskId(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallerWhoMaySeeEveryConsumptionGetsTheUnnarrowedLists() {
        access.everything = true;
        when(materialConsumptionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.getAllMaterialConsumptions(0, 10);
        service.getConsumptionsByTask(3L);

        verify(materialConsumptionRepository).findByTaskId(3L);
        verify(materialConsumptionRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(materialConsumptionRepository, never()).findAll(any(Specification.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallerWhoMaySeeNoConsumptionIsNotQueriedFor() {
        access.nothing = true;
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 0, 0);

        assertThat(service.getAllMaterialConsumptions(0, 10)).isEmpty();
        assertThat(service.getConsumptionsByMaterial(MATERIAL)).isEmpty();
        assertThat(service.getConsumptionsByType(MaterialConsumptionType.TRANSFERRED, 0, 10)).isEmpty();
        assertThat(service.getConsumptionsByDateRange(from, to)).isEmpty();
        assertThat(service.getConsumptionsByTask(3L, 0, 10)).isEmpty();
        verify(materialConsumptionRepository, never()).findAll(any(Pageable.class));
        verify(materialConsumptionRepository, never()).findAll(any(Specification.class));
        verify(materialConsumptionRepository, never()).findByMaterialId(any());
        verify(materialConsumptionRepository, never()).findByConsumptionDateBetween(any(), any());
    }

    private void lookups(Long projectId) {
        Material material = new Material();
        material.setId(MATERIAL);
        Employee creator = new Employee();
        creator.setId(CREATOR);
        Project project = new Project();
        project.setId(projectId);
        when(materialRepository.findByIdAndOrganization_Id(MATERIAL, ORG)).thenReturn(Optional.of(material));
        when(employeeRepository.findByIdAndOrganizationId(CREATOR, ORG)).thenReturn(Optional.of(creator));
        when(projectRepository.findByIdAndOrganization_Id(projectId, ORG)).thenReturn(Optional.of(project));
    }

    private static MaterialConsumptionCreationDto issue(Long projectId) {
        MaterialConsumptionCreationDto issue = new MaterialConsumptionCreationDto();
        issue.setConsumptionDate(LocalDateTime.of(2026, 11, 1, 10, 0));
        issue.setMaterialId(MATERIAL);
        issue.setQuantity(5);
        issue.setConsumptionType("USED_FROM_STOCK");
        issue.setProjectId(projectId);
        issue.setCreatedBy(CREATOR);
        return issue;
    }

    private MaterialConsumption consumption(Long id, Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setId(id);
        consumption.setOrganization(organization);
        consumption.setProject(project);
        return consumption;
    }

    /** Grants read and create on one project's consumption, and nothing else. */
    private static final class OneProjectAccess implements AccessService {

        boolean everything;
        boolean nothing;
        boolean mayCreate = true;

        private Set<String> held() {
            return mayCreate
                    ? Set.of(AccessPermission.READ, AccessPermission.CREATE)
                    : Set.of(AccessPermission.READ);
        }

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return true;
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return !nothing && held().contains(permission.action());
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            if (nothing || !held().contains(permission.action())) {
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
