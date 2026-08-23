package org.tornotron.echno_backend.material;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.material.mapper.MaterialMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.material.dto.MaterialCreationDto;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CRUD and stock-aware reads for the material catalogue.
 *
 * <p>Maintains material records within the current tenant and enforces SKU uniqueness. When
 * a material is created with a positive opening stock, this service also writes the opening
 * balance inventory transaction and seeds the CurrentStock row in the same transaction. The
 * stock-aware read methods combine a material with its quantity and value on hand from
 * {@link org.tornotron.echno_backend.inventoryTransaction.InventoryService}.
 */
@Service
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final org.tornotron.echno_backend.inventoryTransaction.InventoryService inventoryService;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final MaterialMapper materialMapper;
    private final UserContextService userContextService;


    public MaterialService(MaterialRepository materialRepository,
                           org.tornotron.echno_backend.inventoryTransaction.InventoryService inventoryService,
                           InventoryTransactionRepository inventoryTransactionRepository,
                           TenantEntityHelper tenantEntityHelper, EmployeeRepository employeeRepository,
                           ProjectRepository projectRepository, StorageLocationRepository storageLocationRepository,
                           MaterialMapper materialMapper, UserContextService userContextService) {
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.materialMapper = materialMapper;
        this.userContextService = userContextService;
    }

    /**
     * Creates a material and, when an opening stock is given, seeds its starting balance.
     *
     * <p>Resolves the creating employee and rejects a duplicate SKU. If the opening stock is
     * positive, the project (and optional storage location) are resolved, an
     * {@code OPENING_BALANCE} inventory transaction is written, and the CurrentStock row is
     * seeded, all within the same transaction.
     *
     * @param creationDto The material fields, plus optional opening stock, project, location, and unit cost.
     * @return The created material as a DTO.
     * @throws ResourceNotFoundException if the creating employee, or the referenced project or storage location, is not found in this organization.
     * @throws DuplicateResourceException if the given SKU already exists in this organization.
     */
    @Transactional
    public MaterialDto createMaterial(MaterialCreationDto creationDto) {
        Employee createdBy = employeeRepository.findByIdAndOrganizationId(creationDto.getCreatedBy(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + creationDto.getCreatedBy() + " was not found in this organization"));
        // Check for duplicate SKU if provided
        if (creationDto.getSku() != null && materialRepository.existsBySkuAndOrganization_Id(creationDto.getSku(),TenantContext.getCurrentOrgId())) {
            throw new DuplicateResourceException("Material with SKU " + creationDto.getSku() + " already exists");
        }

        Material material = new Material();
        material.setSku(creationDto.getSku());
        material.setMaterialName(creationDto.getMaterialName());
        material.setUnit(creationDto.getUnit());
        material.setCreatedBy(createdBy);
        material.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        material.setDescription(creationDto.getDescription());
        material.setHsn(creationDto.getHsn());
        material.setMoq(creationDto.getMoq());
        material.setOpeningStock(creationDto.getOpeningStock());
        material.setMinStock(creationDto.getMinStock());
        material.setMaxStock(creationDto.getMaxStock());
        material.setSafetyStock(creationDto.getSafetyStock());
        material.setReorderLevel(creationDto.getReorderLevel());

        material = materialRepository.save(material);

        // Seed CurrentStock and create InventoryTransaction for opening balance
        if (creationDto.getOpeningStock() != null && creationDto.getOpeningStock() > 0) {
            Project project = projectRepository.findByIdAndOrganization_Id(
                            creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Project with ID " + creationDto.getProjectId() + " was not found in this organization"));

            StorageLocation storageLocation = null;
            if (creationDto.getStorageLocationId() != null) {
                storageLocation = storageLocationRepository.findByIdAndOrganization_Id(
                                creationDto.getStorageLocationId(), TenantContext.getCurrentOrgId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Storage location with ID " + creationDto.getStorageLocationId() + " was not found in this organization"));
            }

            Double quantity = creationDto.getOpeningStock().doubleValue();
            BigDecimal unitCost = creationDto.getUnitCost();

            InventoryTransaction transaction = new InventoryTransaction();
            transaction.setTransactionDate(java.time.LocalDateTime.now());
            transaction.setMaterial(material);
            transaction.setOpeningStock(0.0);
            transaction.setQuantityChanged(quantity);
            transaction.setClosingStock(quantity);
            transaction.setTransactionType(InventoryTransactionType.OPENING_BALANCE);
            transaction.setReferenceNumber("OB-" + material.getId());
            transaction.setRemarks("Opening balance for material: " + material.getMaterialName());
            transaction.setCreatedBy(createdBy);
            transaction.setProject(project);
            transaction.setStorageLocation(storageLocation);
            transaction.setOrganization(material.getOrganization());
            transaction.setUnitCost(unitCost);

            inventoryTransactionRepository.save(transaction);

            inventoryService.updateCurrentStock(material, project, storageLocation,
                    material.getOrganization(), quantity, unitCost);
        }

        return materialMapper.toDto(material);
    }

    /**
     * Retrieves a single material by its id within the current tenant.
     *
     * @param id The id of the material to retrieve.
     * @return The material as a DTO.
     * @throws ResourceNotFoundException if no material with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public MaterialDto getMaterialById(Long id) {
        Material material = materialRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + id + " was not found in this organization"));
        return materialMapper.toDto(material);
    }

    @Transactional(readOnly = true)
    public List<MaterialDto> getAllMaterials() {
        return materialRepository.findAll().stream()
                .map(material -> materialMapper.toDto(material))
                .collect(Collectors.toList());
    }

    /**
     * Retrieves materials one page at a time, ordered by name.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of materials per page.
     * @return A page of material DTOs ordered by material name ascending.
     */
    @Transactional(readOnly = true)
    public Page<MaterialDto> getAllMaterials(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "materialName"));
        return materialRepository.findAll(pageable)
                .map(material -> materialMapper.toDto(material));
    }

    /**
     * Finds materials whose name contains the given text, case-insensitively.
     *
     * @param name The substring to match against material names.
     * @return The matching materials as DTOs.
     */
    @Transactional(readOnly = true)
    public List<MaterialDto> searchMaterialsByName(String name) {
        return materialRepository.findByMaterialNameContainingIgnoreCase(name).stream()
                .map(material -> materialMapper.toDto(material))
                .collect(Collectors.toList());
    }

    /**
     * Applies a partial update to a material.
     *
     * <p>Only the non-null fields on the update DTO are changed. A changed SKU is checked
     * for uniqueness before being applied. Stock levels held elsewhere are not affected.
     *
     * @param id The id of the material to update.
     * @param updateDto The fields to change.
     * @return The updated material as a DTO.
     * @throws ResourceNotFoundException if no material with the given id exists in this organization.
     * @throws DuplicateResourceException if the new SKU already exists in this organization.
     */
    @Transactional
    public MaterialDto updateMaterial(Long id, org.tornotron.echno_backend.material.dto.MaterialUpdateDto updateDto) {
        Material material = materialRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + id + " was not found in this organization"));

        // Check SKU uniqueness if changed
        if (updateDto.getSku() != null && !updateDto.getSku().equals(material.getSku())) {
            if (materialRepository.existsBySkuAndOrganization_Id(updateDto.getSku(),TenantContext.getCurrentOrgId())) {
                throw new DuplicateResourceException("Material with SKU " + updateDto.getSku() + " already exists");
            }
            material.setSku(updateDto.getSku());
        }

        if (updateDto.getMaterialName() != null) {
            material.setMaterialName(updateDto.getMaterialName());
        }

        if (updateDto.getUnit() != null) {
            material.setUnit(updateDto.getUnit());
        }

        if (updateDto.getDescription() != null) {
            material.setDescription(updateDto.getDescription());
        }

        if (updateDto.getHsn() != null) {
            material.setHsn(updateDto.getHsn());
        }

        if (updateDto.getMoq() != null) {
            material.setMoq(updateDto.getMoq());
        }

        if (updateDto.getMinStock() != null) {
            material.setMinStock(updateDto.getMinStock());
        }

        if (updateDto.getMaxStock() != null) {
            material.setMaxStock(updateDto.getMaxStock());
        }

        if (updateDto.getSafetyStock() != null) {
            material.setSafetyStock(updateDto.getSafetyStock());
        }

        if (updateDto.getReorderLevel() != null) {
            material.setReorderLevel(updateDto.getReorderLevel());
        }

        material = materialRepository.save(material);
        return materialMapper.toDto(material);
    }

    /**
     * Deletes a material within the current tenant.
     *
     * @param id The id of the material to delete.
     * @throws ResourceNotFoundException if no material with the given id exists in this organization.
     */
    @Transactional
    public void deleteMaterial(Long id) {
        if (!materialRepository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Material with ID " + id + " was not found in this organization");
        }
        materialRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
    }

    /**
     * Retrieves a material along with its quantity and value on hand at a project.
     *
     * @param id The id of the material to retrieve.
     * @param projectId The project to report stock for.
     * @return The material with its current stock and stock value at the project.
     * @throws ResourceNotFoundException if no material with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public MaterialWithStockDto getMaterialWithCurrentStock(Long id, Long projectId) {
        Material material = materialRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + id + " was not found in this organization"));

        Double currentStock = inventoryService.getCurrentStock(id, projectId);
        BigDecimal stockValue = inventoryService.getStockValue(id, projectId);
        return materialMapper.toWithStockDto(material, currentStock, stockValue);
    }

    /**
     * Retrieves a material along with its quantity and value on hand across all projects.
     *
     * @param id The id of the material to retrieve.
     * @return The material with its aggregate current stock and stock value.
     * @throws ResourceNotFoundException if no material with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public MaterialWithStockDto getMaterialWithAggregateStock(Long id) {
        Material material = materialRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + id + " was not found in this organization"));

        Double currentStock = inventoryService.getAggregateStock(id);
        BigDecimal stockValue = inventoryService.getAggregateStockValue(id);
        return materialMapper.toWithStockDto(material, currentStock, stockValue);
    }

    /**
     * Retrieves a material along with its quantity and value on hand at one storage location.
     *
     * @param id The id of the material to retrieve.
     * @param projectId The project the location belongs to.
     * @param storageLocationId The storage location to report stock for.
     * @return The material with its current stock and stock value at the location.
     * @throws ResourceNotFoundException if no material with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public MaterialWithStockDto getMaterialStockAtLocation(Long id, Long projectId, Long storageLocationId) {
        Material material = materialRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + id + " was not found in this organization"));

        Double currentStock = inventoryService.getStockAtLocation(id, projectId, storageLocationId);
        BigDecimal stockValue = inventoryService.getStockValueAtLocation(id, projectId, storageLocationId);
        return materialMapper.toWithStockDto(material, currentStock, stockValue);
    }
}
