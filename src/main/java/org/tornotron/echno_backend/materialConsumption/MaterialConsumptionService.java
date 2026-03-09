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

    public MaterialConsumptionService(MaterialConsumptionRepository materialConsumptionRepository,
                                      MaterialRepository materialRepository,
                                      InventoryService inventoryService,
                                      ApplicationEventPublisher eventPublisher,
                                      FileStorageService fileStorageService,
                                      TenantEntityHelper tenantEntityHelper, EmployeeRepository employeeRepository) {
        this.materialConsumptionRepository = materialConsumptionRepository;
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public MaterialConsumptionDto createMaterialConsumption(MaterialConsumptionCreationDto creationDto) {
        // Validate material exists
        Material material = materialRepository.findById(creationDto.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + creationDto.getMaterialId()));

        // Validate user exists
        Employee createdBy = employeeRepository.findByIdAndOrganizationId(creationDto.getCreatedBy(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + creationDto.getCreatedBy()));

        // CRITICAL: Validate sufficient stock before consumption
        inventoryService.validateSufficientStock(creationDto.getMaterialId(), creationDto.getQuantity());

        // Create material consumption
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setConsumptionDate(creationDto.getConsumptionDate());
        consumption.setMaterial(material);
        consumption.setQuantity(creationDto.getQuantity());
        consumption.setConsumptionType(MaterialConsumptionType.valueOf(creationDto.getConsumptionType()));
        consumption.setDetails(creationDto.getDetails());
        consumption.setCreatedBy(createdBy);
        consumption.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        consumption = materialConsumptionRepository.save(consumption);

        // Publish MaterialConsumedEvent for automatic inventory update
        eventPublisher.publishEvent(new MaterialConsumedEvent(this, consumption));

        return MaterialConsumptionDtoConvertor.convertToDto(consumption, fileStorageService);
    }

    @Transactional(readOnly = true)
    public MaterialConsumptionDto getMaterialConsumptionById(Long id) {
        MaterialConsumption consumption = materialConsumptionRepository.findById(id)
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
}
