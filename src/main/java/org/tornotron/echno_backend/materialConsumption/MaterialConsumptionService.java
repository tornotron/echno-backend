package org.tornotron.echno_backend.materialConsumption;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.materialConsumption.mapper.MaterialConsumptionMapper;
import org.tornotron.echno_backend.common.events.MaterialConsumedEvent;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionCreationDto;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionDto;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationScope;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Records material consumption and exposes queries over past consumption.
 *
 * <p>Recording a consumption first checks that enough stock is available on the balance row
 * the draw-down will write: the storage location's row when a location is given, otherwise
 * the project's unlocated row. It then persists the record and publishes a
 * {@link MaterialConsumedEvent} so the inventory ledger draws the stock down.
 *
 * <p>A storage location on another project is refused, and an organisation-level location
 * (one that names no project) is accepted from any project. See
 * {@link org.tornotron.echno_backend.storageLocation.StorageLocationScope}.
 */
@Service
public class MaterialConsumptionService {

    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final MaterialRepository materialRepository;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final MaterialConsumptionMapper materialConsumptionMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final TaskRepository taskRepository;

    public MaterialConsumptionService(MaterialConsumptionRepository materialConsumptionRepository,
                                      MaterialRepository materialRepository,
                                      InventoryService inventoryService,
                                      ApplicationEventPublisher eventPublisher,
                                      MaterialConsumptionMapper materialConsumptionMapper,
                                      TenantEntityHelper tenantEntityHelper,
                                      EmployeeRepository employeeRepository,
                                      ProjectRepository projectRepository,
                                      StorageLocationRepository storageLocationRepository,
                                      TaskRepository taskRepository) {
        this.materialConsumptionRepository = materialConsumptionRepository;
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
        this.materialConsumptionMapper = materialConsumptionMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.taskRepository = taskRepository;
    }

    /**
     * Records a material consumption after checking stock, and triggers the stock decrease.
     *
     * <p>Resolves the material, creator, project and storage location, checks that the
     * location may be used from that project, then validates sufficient stock on the balance
     * row the draw-down will write: the location's row when a location is supplied, the
     * project's unlocated row otherwise. An optional task must belong to the same project.
     * After saving, a {@link MaterialConsumedEvent} is published so inventory is reduced.
     *
     * @param creationDto The consumption details, including material, quantity, project, and optional storage location and task.
     * @return The created consumption record as a DTO.
     * @throws ResourceNotFoundException if the material, creator, project, storage location, or task is not found in this organization.
     * @throws org.tornotron.echno_backend.common.exception.InvalidRequestException if the storage location belongs to a different project.
     * @throws InsufficientStockException if there is no balance to draw on, or it is below the consumed quantity.
     * @throws IllegalArgumentException if the given task does not belong to the given project.
     */
    @Transactional
    public MaterialConsumptionDto createMaterialConsumption(MaterialConsumptionCreationDto creationDto) {
        // Validate material exists
        Material material = materialRepository.findByIdAndOrganization_Id(creationDto.getMaterialId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + creationDto.getMaterialId() + " was not found in this organization"));

        Employee createdBy = employeeRepository.findByIdAndOrganizationId(creationDto.getCreatedBy(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + creationDto.getCreatedBy() + " was not found in this organization"));

        // Validate project
        Project project = projectRepository.findByIdAndOrganization_Id(creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + creationDto.getProjectId() + " was not found in this organization"));

        // Resolve the storage location before the stock check, because the location decides
        // which balance row the check has to read: the consumption draws down exactly one row
        // and the check has to be asked about that same row.
        StorageLocation storageLocation = null;
        if (creationDto.getStorageLocationId() != null) {
            storageLocation = storageLocationRepository.findByIdAndOrganization_Id(
                            creationDto.getStorageLocationId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Storage location with ID " + creationDto.getStorageLocationId() + " was not found in this organization"));
            // Nothing used to check the location against the project, so a location on another
            // project (or none) could be booked here and the balance row could never exist.
            StorageLocationScope.requireUsableFromProject(storageLocation, project.getId());
        }

        // CRITICAL: Validate sufficient stock before consumption, against the row the
        // consumption will write. With no location that is the project's unlocated row, not
        // the project total, which would pass on stock held in locations this write cannot
        // reach and drive the unlocated balance negative.
        if (storageLocation != null) {
            inventoryService.validateSufficientStockAtLocation(
                    material.getId(), project.getId(),
                    storageLocation.getId(), creationDto.getQuantity().doubleValue());
        } else {
            inventoryService.validateSufficientUnlocatedStock(
                    material.getId(), project.getId(), creationDto.getQuantity().doubleValue());
        }

        // Create material consumption
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setConsumptionDate(creationDto.getConsumptionDate());
        consumption.setMaterial(material);
        consumption.setQuantity(creationDto.getQuantity());
        consumption.setConsumptionType(MaterialConsumptionType.valueOf(creationDto.getConsumptionType()));
        consumption.setDetails(creationDto.getDetails());
        consumption.setCreatedBy(createdBy);
        consumption.setProject(project);

        consumption.setStorageLocation(storageLocation);

        // Validate and set task (optional)
        if (creationDto.getTaskId() != null) {
            Task task = taskRepository.findByIdAndOrganization_Id(creationDto.getTaskId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Task with ID " + creationDto.getTaskId() + " was not found in this organization"));
            // Validate task belongs to the same project
            if (!task.getProject().getId().equals(project.getId())) {
                throw new IllegalArgumentException("Task with ID " + task.getId() +
                        " does not belong to project with ID " + project.getId());
            }
            consumption.setTask(task);
        }

        consumption.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        consumption = materialConsumptionRepository.save(consumption);

        // Publish MaterialConsumedEvent for automatic inventory update
        eventPublisher.publishEvent(new MaterialConsumedEvent(this, consumption));

        return materialConsumptionMapper.toDto(consumption);
    }

    /**
     * Retrieves a single consumption record by its id within the current tenant.
     *
     * @param id The id of the consumption record to retrieve.
     * @return The consumption record as a DTO.
     * @throws ResourceNotFoundException if no consumption record with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public MaterialConsumptionDto getMaterialConsumptionById(Long id) {
        MaterialConsumption consumption = materialConsumptionRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material consumption with ID " + id + " was not found in this organization"));
        return materialConsumptionMapper.toDto(consumption);
    }


    /**
     * Retrieves consumption records one page at a time, newest first.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of records per page.
     * @return A page of consumption DTOs ordered by consumption date descending.
     */
    @Transactional(readOnly = true)
    public Page<MaterialConsumptionDto> getAllMaterialConsumptions(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "consumptionDate"));
        return materialConsumptionRepository.findAll(pageable)
                .map(consumption -> materialConsumptionMapper.toDto(consumption));
    }

    /**
     * Lists consumption records for a given material.
     *
     * @param materialId The material whose consumptions to return.
     * @return The matching consumption records as DTOs.
     */
    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getConsumptionsByMaterial(Long materialId) {
        return materialConsumptionRepository.findByMaterialId(materialId).stream()
                .map(consumption -> materialConsumptionMapper.toDto(consumption))
                .collect(Collectors.toList());
    }

    /**
     * Lists consumption records of a given consumption type.
     *
     * @param consumptionType The consumption type to filter by.
     * @return The matching consumption records as DTOs.
     */
    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getConsumptionsByType(MaterialConsumptionType consumptionType) {
        return materialConsumptionRepository.findByConsumptionType(consumptionType).stream()
                .map(consumption -> materialConsumptionMapper.toDto(consumption))
                .collect(Collectors.toList());
    }

    /**
     * Lists consumption records within an inclusive date range.
     *
     * @param startDate Start of the consumption-date range.
     * @param endDate End of the consumption-date range.
     * @return The matching consumption records as DTOs.
     */
    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getConsumptionsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return materialConsumptionRepository.findByConsumptionDateBetween(startDate, endDate).stream()
                .map(consumption -> materialConsumptionMapper.toDto(consumption))
                .collect(Collectors.toList());
    }

    /**
     * Lists consumption records attributed to a given task.
     *
     * @param taskId The task whose consumptions to return.
     * @return The matching consumption records as DTOs.
     */
    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getConsumptionsByTask(Long taskId) {
        return materialConsumptionRepository.findByTaskId(taskId).stream()
                .map(consumption -> materialConsumptionMapper.toDto(consumption))
                .collect(Collectors.toList());
    }
}
