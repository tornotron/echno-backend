package org.tornotron.echno_backend.goodsReceivedNote;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.goodsReceivedNote.mapper.GoodsReceivedNoteMapper;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteCreationDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteUpdateDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GrnItemDto;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.grnItem.GrnItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GoodsReceivedNoteService {

    private final GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    private final GrnItemRepository grnItemRepository;
    private final VendorRepository vendorRepository;
    private final MaterialRepository materialRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final GoodsReceivedNoteMapper goodsReceivedNoteMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    public GoodsReceivedNoteService(GoodsReceivedNoteRepository goodsReceivedNoteRepository,
                                    GrnItemRepository grnItemRepository,
                                    VendorRepository vendorRepository,
                                    UserRepository userRepository,
                                    MaterialRepository materialRepository,
                                    ApplicationEventPublisher eventPublisher,
                                    GoodsReceivedNoteMapper goodsReceivedNoteMapper,
                                    TenantEntityHelper tenantEntityHelper,
                                    EmployeeRepository employeeRepository,
                                    ProjectRepository projectRepository,
                                    StorageLocationRepository storageLocationRepository, PurchaseOrderRepository purchaseOrderRepository) {
        this.goodsReceivedNoteRepository = goodsReceivedNoteRepository;
        this.grnItemRepository = grnItemRepository;
        this.vendorRepository = vendorRepository;
        this.materialRepository = materialRepository;
        this.eventPublisher = eventPublisher;
        this.goodsReceivedNoteMapper = goodsReceivedNoteMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    @Transactional
    public GoodsReceivedNoteDto createGoodsReceivedNote(GoodsReceivedNoteCreationDto creationDto) {
        // Check for duplicate GRN number
        if (goodsReceivedNoteRepository.existsByGrnNumber(creationDto.getGrnNumber())) {
            throw new DuplicateResourceException("GRN number '" + creationDto.getGrnNumber() + "' is already in use in this organization");
        }


        // Validate vendor
        Vendor vendor = vendorRepository.findByIdAndOrganization_Id(creationDto.getVendorId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor with ID " + creationDto.getVendorId() + " was not found in this organization"));

        Employee receivedBy = employeeRepository.findByIdAndOrganizationId(creationDto.getReceivedByEmployeeId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + creationDto.getReceivedByEmployeeId() + " was not found in this organization"));

        // Validate project
        Project project = projectRepository.findByIdAndOrganization_Id(creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + creationDto.getProjectId() + " was not found in this organization"));

        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(creationDto.getPurchaseOrderId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + creationDto.getPurchaseOrderId() + " was not found in this organization"));

        // Create GRN
        GoodsReceivedNote grn = new GoodsReceivedNote();
        grn.setGrnNumber(creationDto.getGrnNumber());
        grn.setReceivedOn(creationDto.getReceivedOn());
        grn.setReceivedBy(receivedBy);
        grn.setVendor(vendor);
        grn.setDeliveryChallanNumber(creationDto.getDeliveryChallanNumber());
        grn.setInvoiceNumber(creationDto.getInvoiceNumber());
        grn.setInvoiceAmount(creationDto.getInvoiceAmount());
        grn.setProject(project);
        grn.setPurchaseOrder(purchaseOrder);


        // Validate and set storage location (optional)
        if (creationDto.getStorageLocationId() != null) {
            StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(
                            creationDto.getStorageLocationId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Storage location with ID " + creationDto.getStorageLocationId() + " was not found in this organization"));
            grn.setStorageLocation(storageLocation);
        }

        grn.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        // Save GRN first
        grn = goodsReceivedNoteRepository.save(grn);

        // Create GRN items
        List<GrnItem> items = new ArrayList<>();
        for (GrnItemDto itemDto : creationDto.getItems()) {
            Material material = materialRepository.findByIdAndOrganization_Id(itemDto.getMaterialId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + itemDto.getMaterialId() + " was not found in this organization"));

            GrnItem grnItem = new GrnItem();
            grnItem.setGoodsReceivedNote(grn);
            grnItem.setMaterial(material);
            grnItem.setOrderedQuantity(itemDto.getOrderedQuantity());
            grnItem.setReceivedQuantity(itemDto.getReceivedQuantity());
            grnItem.setUnitCost(itemDto.getUnitCost());
            grnItem.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

            items.add(grnItem);
        }

        grnItemRepository.saveAll(items);
        grn.setItems(items);

        // Publish GrnCreatedEvent for automatic inventory update
        eventPublisher.publishEvent(new GrnCreatedEvent(this, grn));

        return goodsReceivedNoteMapper.toDto(grn);
    }

    @Transactional
    public GoodsReceivedNoteDto updateGoodsReceivedNote(GoodsReceivedNoteUpdateDto updateDto) {
        GoodsReceivedNote grn = goodsReceivedNoteRepository.findByIdAndOrganization_Id(updateDto.getId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("GRN with ID " + updateDto.getId() + " was not found in this organization"));

        if (updateDto.getReceivedOn() != null) {
            grn.setReceivedOn(updateDto.getReceivedOn());
        }

        if (updateDto.getReceivedByEmployeeId() != null) {
            Employee receivedBy = employeeRepository.findByIdAndOrganizationId(updateDto.getReceivedByEmployeeId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + updateDto.getReceivedByEmployeeId() + " was not found in this organization"));
            grn.setReceivedBy(receivedBy);
        }

        if (updateDto.getDeliveryChallanNumber() != null) {
            grn.setDeliveryChallanNumber(updateDto.getDeliveryChallanNumber());
        }

        if (updateDto.getInvoiceNumber() != null) {
            grn.setInvoiceNumber(updateDto.getInvoiceNumber());
        }

        if (updateDto.getInvoiceAmount() != null) {
            grn.setInvoiceAmount(updateDto.getInvoiceAmount());
        }

        if (updateDto.getStorageLocationId() != null) {
            StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(
                            updateDto.getStorageLocationId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Storage location with ID " + updateDto.getStorageLocationId() + " was not found in this organization"));
            grn.setStorageLocation(storageLocation);
        }

        grn = goodsReceivedNoteRepository.save(grn);
        return goodsReceivedNoteMapper.toDto(grn);
    }

    @Transactional(readOnly = true)
    public GoodsReceivedNoteDto getGrnById(Long id) {
        GoodsReceivedNote grn = goodsReceivedNoteRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("GRN with ID " + id + " was not found in this organization"));
        return goodsReceivedNoteMapper.toDto(grn);
    }

    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getAllGrns() {
        return goodsReceivedNoteRepository.findAll().stream()
                .map(grn -> goodsReceivedNoteMapper.toDto(grn))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<GoodsReceivedNoteDto> getAllGrns(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "receivedOn"));
        return goodsReceivedNoteRepository.findAll(pageable)
                .map(grn -> goodsReceivedNoteMapper.toDto(grn));
    }

    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getGrnsByVendor(Long vendorId) {
        return goodsReceivedNoteRepository.findByVendorId(vendorId).stream()
                .map(grn -> goodsReceivedNoteMapper.toDto(grn))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getGrnsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return goodsReceivedNoteRepository.findByReceivedOnBetween(startDate, endDate).stream()
                .map(grn -> goodsReceivedNoteMapper.toDto(grn))
                .collect(Collectors.toList());
    }
}
