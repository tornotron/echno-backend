package org.tornotron.echno_backend.materialConsumption;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.exception.InsufficientStockException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStock;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionCreationDto;
import org.tornotron.echno_backend.materialConsumption.mapper.MaterialConsumptionMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.task.TaskRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consumption path against a real {@link InventoryService} over a mocked stock
 * repository, so the balance row the check reads is the thing under test rather than a
 * mock's say-so. This is the shape of Anand's QA report: a form showing 60 MT, an API
 * refusing 1 MT, and a storage location paired with a project it does not belong to.
 */
@ExtendWith(MockitoExtension.class)
class MaterialConsumptionStockScopeTest {

    private static final Long ORG = 100L;
    private static final Long MATERIAL = 2L;
    private static final Long EMPLOYEE = 7L;
    private static final Long PROJECT = 3L;
    private static final Long LOCATION = 14L;
    private static final Long OTHER_PROJECT = 9L;

    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private CurrentStockRepository currentStockRepository;
    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MaterialConsumptionMapper materialConsumptionMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private TaskRepository taskRepository;

    private MaterialConsumptionService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        InventoryService inventoryService = new InventoryService(currentStockRepository,
                inventoryTransactionRepository, materialRepository, storageLocationRepository);
        service = new MaterialConsumptionService(materialConsumptionRepository, materialRepository,
                inventoryService, eventPublisher, materialConsumptionMapper, tenantEntityHelper,
                employeeRepository, projectRepository, storageLocationRepository, taskRepository);

        Material material = new Material();
        material.setId(MATERIAL);
        Employee employee = new Employee();
        employee.setId(EMPLOYEE);
        Project project = new Project();
        project.setId(PROJECT);
        Organization org = new Organization();
        org.setId(ORG);
        lenient().when(materialRepository.findByIdAndOrganization_Id(MATERIAL, ORG)).thenReturn(Optional.of(material));
        lenient().when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE, ORG)).thenReturn(Optional.of(employee));
        lenient().when(projectRepository.findByIdAndOrganization_Id(PROJECT, ORG)).thenReturn(Optional.of(project));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(org);
        lenient().when(materialConsumptionRepository.save(any(MaterialConsumption.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MaterialConsumptionCreationDto dto(Long storageLocationId) {
        MaterialConsumptionCreationDto dto = new MaterialConsumptionCreationDto();
        dto.setConsumptionDate(LocalDateTime.now());
        dto.setMaterialId(MATERIAL);
        dto.setQuantity(1);
        dto.setConsumptionType("USED_FROM_STOCK");
        dto.setProjectId(PROJECT);
        dto.setCreatedBy(EMPLOYEE);
        dto.setStorageLocationId(storageLocationId);
        return dto;
    }

    private StorageLocation location(Long id, Long owningProjectId) {
        StorageLocation location = new StorageLocation();
        location.setId(id);
        if (owningProjectId != null) {
            Project owner = new Project();
            owner.setId(owningProjectId);
            location.setProject(owner);
        }
        when(storageLocationRepository.findByIdAndOrganization_Id(id, ORG)).thenReturn(Optional.of(location));
        return location;
    }

    private CurrentStock stockRow(double quantity) {
        CurrentStock row = new CurrentStock();
        row.setCurrentQuantity(quantity);
        return row;
    }

    @Test
    void aLocationThatHasNeverHeldTheMaterialIsNamedAsSuchRatherThanReportedAsZero() {
        location(LOCATION, PROJECT);
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationId(MATERIAL, PROJECT, LOCATION))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.createMaterialConsumption(dto(LOCATION)))
                .withMessageContaining("has ever been held")
                .withMessageContaining("goods receipt");

        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void aLocationThatHasRunDownToZeroIsStillReportedAsAStockOut() {
        location(LOCATION, PROJECT);
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationId(MATERIAL, PROJECT, LOCATION))
                .thenReturn(Optional.of(stockRow(0.0)));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.createMaterialConsumption(dto(LOCATION)))
                .withMessageContaining("Insufficient stock")
                .withMessageContaining("Available: 0.00");
    }

    @Test
    void aStorageLocationBelongingToAnotherProjectIsRefused() {
        location(LOCATION, OTHER_PROJECT);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.createMaterialConsumption(dto(LOCATION)))
                .withMessageContaining("belongs to project with ID 9");

        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void anOrganisationLevelLocationIsAcceptedFromAnyProject() {
        location(LOCATION, null);
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationId(MATERIAL, PROJECT, LOCATION))
                .thenReturn(Optional.of(stockRow(60.0)));

        assertThatCode(() -> service.createMaterialConsumption(dto(LOCATION))).doesNotThrowAnyException();

        verify(materialConsumptionRepository).save(any());
    }

    @Test
    void aConsumptionWithNoLocationIsRefusedWhenTheProjectHoldsItsStockInsideLocations() {
        // The project total is 60, all of it inside storage locations. The draw-down writes
        // the project's unlocated row, which holds nothing, so passing the project total here
        // is what took row 15 on staging to -30.
        lenient().when(currentStockRepository.sumCurrentQuantityByMaterialAndProject(MATERIAL, PROJECT))
                .thenReturn(60.0);
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationIsNull(MATERIAL, PROJECT))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.createMaterialConsumption(dto(null)))
                .withMessageContaining("outside a storage location")
                .withMessageContaining("name the location");

        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void aConsumptionWithNoLocationIsAllowedAgainstTheUnlocatedBalance() {
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationIsNull(MATERIAL, PROJECT))
                .thenReturn(Optional.of(stockRow(4.0)));

        assertThatCode(() -> service.createMaterialConsumption(dto(null))).doesNotThrowAnyException();

        verify(materialConsumptionRepository).save(any());
    }
}
