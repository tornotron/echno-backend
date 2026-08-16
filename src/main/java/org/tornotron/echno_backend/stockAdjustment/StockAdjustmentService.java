package org.tornotron.echno_backend.stockAdjustment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentCreationDto;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentDto;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentLineItemCreationDto;
import org.tornotron.echno_backend.stockAdjustment.mapper.StockAdjustmentMapper;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CRUD + list for stock-adjustment documents. MVP scope: the document is persisted
 * only; this service does NOT touch {@code CurrentStock} or post inventory
 * transactions. That integration is deferred.
 */
@Service
public class StockAdjustmentService {

    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockAdjustmentMapper stockAdjustmentMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final MaterialRepository materialRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ProjectRepository projectRepository;

    public StockAdjustmentService(StockAdjustmentRepository stockAdjustmentRepository,
                                  StockAdjustmentMapper stockAdjustmentMapper,
                                  TenantEntityHelper tenantEntityHelper,
                                  MaterialRepository materialRepository,
                                  StorageLocationRepository storageLocationRepository,
                                  ProjectRepository projectRepository) {
        this.stockAdjustmentRepository = stockAdjustmentRepository;
        this.stockAdjustmentMapper = stockAdjustmentMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.materialRepository = materialRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public StockAdjustmentDto create(StockAdjustmentCreationDto creationDto) {
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        StockAdjustment stockAdjustment = new StockAdjustment();
        stockAdjustment.setOrganization(organization);
        applyHeaderFields(stockAdjustment, creationDto);
        applyLineItems(stockAdjustment, creationDto.getLineItems(), organization);
        StockAdjustment saved = stockAdjustmentRepository.saveAndFlush(stockAdjustment);
        return stockAdjustmentMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public StockAdjustmentDto getById(Long id) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        return stockAdjustmentMapper.toDto(stockAdjustment);
    }

    @Transactional(readOnly = true)
    public List<StockAdjustmentDto> getAll() {
        return stockAdjustmentRepository.findAll().stream()
                .map(stockAdjustmentMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<StockAdjustmentDto> getAll(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return stockAdjustmentRepository.findAll(pageable)
                .map(stockAdjustmentMapper::toDto);
    }

    @Transactional
    public StockAdjustmentDto update(Long id, StockAdjustmentCreationDto creationDto) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        Organization organization = stockAdjustment.getOrganization();

        applyHeaderFields(stockAdjustment, creationDto);

        // Replace the line-item collection: clear in place (orphanRemoval deletes the old
        // rows) and re-add from the request, keeping Hibernate's collection tracking intact.
        stockAdjustment.getLineItems().clear();
        applyLineItems(stockAdjustment, creationDto.getLineItems(), organization);

        // saveAndFlush before mapping so the freshly inserted line-item ids are populated
        // on the returned DTO (documented gotcha: without the flush the child ids are null).
        StockAdjustment saved = stockAdjustmentRepository.saveAndFlush(stockAdjustment);
        return stockAdjustmentMapper.toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        stockAdjustmentRepository.delete(stockAdjustment);
    }

    /** Copies the header scalars from the creation DTO, resolving the location and project. */
    private void applyHeaderFields(StockAdjustment stockAdjustment, StockAdjustmentCreationDto dto) {
        stockAdjustment.setAdjustmentNumber(dto.getAdjustmentNumber());
        stockAdjustment.setType(dto.getType());
        stockAdjustment.setStatus(dto.getStatus());
        stockAdjustment.setAdjustmentDate(dto.getAdjustmentDate());
        stockAdjustment.setEffectiveDate(dto.getEffectiveDate());
        stockAdjustment.setTotalAdjustmentValue(dto.getTotalAdjustmentValue());
        stockAdjustment.setPrimaryReason(dto.getPrimaryReason());
        stockAdjustment.setJustification(dto.getJustification());
        stockAdjustment.setPhysicalCountDate(dto.getPhysicalCountDate());
        stockAdjustment.setPhysicalCountBy(dto.getPhysicalCountBy());
        stockAdjustment.setCountMethod(dto.getCountMethod());
        stockAdjustment.setSubmittedBy(dto.getSubmittedBy());
        stockAdjustment.setTotalVarianceQuantity(dto.getTotalVarianceQuantity());

        stockAdjustment.setLocation(resolveLocation(dto.getLocationId()));
        stockAdjustment.setProject(resolveProject(dto.getProjectId()));
    }

    /** Builds and attaches the line items, resolving each line's material and location. */
    private void applyLineItems(StockAdjustment stockAdjustment,
                                List<StockAdjustmentLineItemCreationDto> lineItemDtos,
                                Organization organization) {
        if (lineItemDtos == null) {
            return;
        }
        for (StockAdjustmentLineItemCreationDto lineDto : lineItemDtos) {
            StockAdjustmentLineItem item = new StockAdjustmentLineItem();
            item.setDescription(lineDto.getDescription());
            item.setSystemQuantity(lineDto.getSystemQuantity());
            item.setPhysicalQuantity(lineDto.getPhysicalQuantity());
            item.setAdjustmentQuantity(lineDto.getAdjustmentQuantity());
            item.setUnit(lineDto.getUnit());
            item.setUnitValue(lineDto.getUnitValue());
            item.setTotalAdjustmentValue(lineDto.getTotalAdjustmentValue());
            item.setReason(lineDto.getReason());
            item.setReasonDetails(lineDto.getReasonDetails());
            item.setBinLocation(lineDto.getBinLocation());
            item.setNotes(lineDto.getNotes());
            item.setMaterial(resolveMaterial(lineDto.getMaterialId()));
            item.setLocation(resolveLocation(lineDto.getLocationId()));
            item.setOrganization(organization);
            stockAdjustment.addLineItem(item);
        }
    }

    private StorageLocation resolveLocation(Long locationId) {
        if (locationId == null) {
            return null;
        }
        return storageLocationRepository.findByIdAndOrganization_Id(locationId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Storage location with ID " + locationId + " was not found in this organization"));
    }

    private Project resolveProject(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectRepository.findByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project with ID " + projectId + " was not found in this organization"));
    }

    private Material resolveMaterial(Long materialId) {
        if (materialId == null) {
            return null;
        }
        return materialRepository.findByIdAndOrganization_Id(materialId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Material with ID " + materialId + " was not found in this organization"));
    }
}
