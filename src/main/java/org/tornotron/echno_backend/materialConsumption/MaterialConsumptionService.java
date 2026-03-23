package org.tornotron.echno_backend.materialConsumption;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.MaterialConsumptionDtoConvertor;
import org.tornotron.echno_backend.common.events.MaterialConsumedEvent;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
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
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MaterialConsumptionService {

    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final MaterialRepository materialRepository;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final FileStorageService fileStorageService;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final TaskRepository taskRepository;

    public MaterialConsumptionService(MaterialConsumptionRepository materialConsumptionRepository,
                                      MaterialRepository materialRepository,
                                      InventoryService inventoryService,
                                      ApplicationEventPublisher eventPublisher,
                                      FileStorageService fileStorageService,
                                      TenantEntityHelper tenantEntityHelper,
                                      EmployeeRepository employeeRepository,
                                      ProjectRepository projectRepository,
                                      StorageLocationRepository storageLocationRepository,
                                      TaskRepository taskRepository) {
        this.materialConsumptionRepository = materialConsumptionRepository;
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public MaterialConsumptionDto createMaterialConsumption(MaterialConsumptionCreationDto creationDto) {
        // Validate material exists
        Material material = materialRepository.findByIdAndOrganization_Id(creationDto.getMaterialId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + creationDto.getMaterialId()));

        Employee createdBy = employeeRepository.findByIdAndOrganizationId(creationDto.getCreatedBy(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + creationDto.getCreatedBy()));

        // Validate project
        Project project = projectRepository.findByIdAndOrganization_Id(creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + creationDto.getProjectId()));

        // CRITICAL: Validate sufficient stock before consumption
        // Use storage-location-level validation when a storage location is specified
        if (creationDto.getStorageLocationId() != null) {
            inventoryService.validateSufficientStockAtLocation(
                    creationDto.getMaterialId(), project.getId(),
                    creationDto.getStorageLocationId(), creationDto.getQuantity().doubleValue());
        } else {
            inventoryService.validateSufficientStock(creationDto.getMaterialId(), project.getId(), creationDto.getQuantity().doubleValue());
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

        // Validate and set storage location (optional)
        if (creationDto.getStorageLocationId() != null) {
            StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(
                            creationDto.getStorageLocationId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Storage location not found with id: " + creationDto.getStorageLocationId()));
            consumption.setStorageLocation(storageLocation);
        }

        // Validate and set task (optional)
        if (creationDto.getTaskId() != null) {
            Task task = taskRepository.findByIdAndOrganization_Id(creationDto.getTaskId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + creationDto.getTaskId()));
            // Validate task belongs to the same project
            if (!task.getProject().getId().equals(project.getId())) {
                throw new IllegalArgumentException("Task with id " + task.getId() +
                        " does not belong to project with id " + project.getId());
            }
            consumption.setTask(task);
        }

        consumption.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        consumption = materialConsumptionRepository.save(consumption);

        // Publish MaterialConsumedEvent for automatic inventory update
        eventPublisher.publishEvent(new MaterialConsumedEvent(this, consumption));

        return MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService);
    }

    @Transactional(readOnly = true)
    public MaterialConsumptionDto getMaterialConsumptionById(Long id) {
        MaterialConsumption consumption = materialConsumptionRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material consumption not found with id: " + id));
        return MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService);
    }

    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getAllMaterialConsumptions() {
        return materialConsumptionRepository.findAll().stream()
                .map(consumption -> MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<MaterialConsumptionDto> getAllMaterialConsumptions(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "consumptionDate"));
        return materialConsumptionRepository.findAll(pageable)
                .map(consumption -> MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService));
    }

    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getConsumptionsByMaterial(Long materialId) {
        return materialConsumptionRepository.findByMaterialId(materialId).stream()
                .map(consumption -> MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getConsumptionsByType(MaterialConsumptionType consumptionType) {
        return materialConsumptionRepository.findByConsumptionType(consumptionType).stream()
                .map(consumption -> MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getConsumptionsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return materialConsumptionRepository.findByConsumptionDateBetween(startDate, endDate).stream()
                .map(consumption -> MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<MaterialConsumptionDto> getConsumptionsByTask(Long taskId) {
        return materialConsumptionRepository.findByTaskId(taskId).stream()
                .map(consumption -> MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService))
                .collect(Collectors.toList());
    }
}
