package org.tornotron.echno_backend.materialConsumption;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.events.MaterialConsumedEvent;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionCreationDto;
import org.tornotron.echno_backend.materialConsumption.mapper.MaterialConsumptionMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MaterialConsumptionService. Repositories, the inventory service, the
 * event publisher, and the mapper are mocked; the entity graph is built in memory. The
 * focus is the logic this service owns before the row is persisted: rejecting unknown
 * referenced entities, rejecting a task that belongs to a different project, choosing the
 * location-scoped vs project-scoped stock check, and publishing the consumed event that
 * drives the inventory depletion.
 */
@ExtendWith(MockitoExtension.class)
class MaterialConsumptionServiceTest {

    private static final Long ORG = 100L;
    private static final Long MATERIAL = 11L;
    private static final Long EMPLOYEE = 7L;
    private static final Long PROJECT = 9L;
    private static final Long LOCATION = 3L;

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

    private MaterialConsumptionService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new MaterialConsumptionService(materialConsumptionRepository, materialRepository,
                inventoryService, eventPublisher, materialConsumptionMapper, tenantEntityHelper,
                employeeRepository, projectRepository, storageLocationRepository, taskRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubMasterLookups() {
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

    private MaterialConsumptionCreationDto baseDto() {
        MaterialConsumptionCreationDto dto = new MaterialConsumptionCreationDto();
        dto.setConsumptionDate(LocalDateTime.now());
        dto.setMaterialId(MATERIAL);
        dto.setQuantity(5);
        dto.setConsumptionType("USED_FROM_STOCK");
        dto.setProjectId(PROJECT);
        dto.setCreatedBy(EMPLOYEE);
        return dto;
    }

    @Test
    void create_unknownMaterial_throwsNotFound() {
        when(materialRepository.findByIdAndOrganization_Id(MATERIAL, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.createMaterialConsumption(baseDto()));

        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void create_withStorageLocation_usesLocationScopedStockCheck() {
        stubMasterLookups();
        StorageLocation location = new StorageLocation();
        location.setId(LOCATION);
        when(storageLocationRepository.findByIdAndOrganization_Id(LOCATION, ORG)).thenReturn(Optional.of(location));

        MaterialConsumptionCreationDto dto = baseDto();
        dto.setStorageLocationId(LOCATION);

        service.createMaterialConsumption(dto);

        verify(inventoryService).validateSufficientStockAtLocation(MATERIAL, PROJECT, LOCATION, 5.0);
        verify(inventoryService, never()).validateSufficientStock(anyLong(), anyLong(), anyDouble());
    }

    @Test
    void create_withoutStorageLocation_usesProjectScopedStockCheck() {
        stubMasterLookups();

        service.createMaterialConsumption(baseDto());

        verify(inventoryService).validateSufficientStock(MATERIAL, PROJECT, 5.0);
        verify(inventoryService, never()).validateSufficientStockAtLocation(anyLong(), anyLong(), anyLong(), anyDouble());
    }

    @Test
    void create_taskFromAnotherProject_throwsIllegalArgument() {
        stubMasterLookups();
        Project otherProject = new Project();
        otherProject.setId(999L);
        Task task = new Task();
        task.setId(21L);
        task.setProject(otherProject);
        when(taskRepository.findByIdAndOrganization_Id(21L, ORG)).thenReturn(Optional.of(task));

        MaterialConsumptionCreationDto dto = baseDto();
        dto.setTaskId(21L);

        assertThatThrownBy(() -> service.createMaterialConsumption(dto))
                .isInstanceOf(IllegalArgumentException.class);

        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void create_happyPath_savesParsesTypeAndPublishesEvent() {
        stubMasterLookups();

        service.createMaterialConsumption(baseDto());

        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(materialConsumptionRepository).save(captor.capture());
        MaterialConsumption saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getConsumptionType())
                .isEqualTo(org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType.USED_FROM_STOCK);
        org.assertj.core.api.Assertions.assertThat(saved.getMaterial().getId()).isEqualTo(MATERIAL);
        org.assertj.core.api.Assertions.assertThat(saved.getOrganization().getId()).isEqualTo(ORG);

        verify(eventPublisher).publishEvent(any(MaterialConsumedEvent.class));
        verify(materialConsumptionMapper).toDto(eq(saved));
    }
}
