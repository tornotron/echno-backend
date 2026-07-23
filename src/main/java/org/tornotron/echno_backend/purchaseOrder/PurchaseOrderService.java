package org.tornotron.echno_backend.purchaseOrder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.PurchaseOrderDtoConvertor;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderCreationDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderUpdateDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final VendorRepository vendorRepository;
    private final IndentRepository indentRepository;
    private final FileStorageService fileStorageService;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final MaterialRepository materialRepository;
    private final IndentItemRepository indentItemRepository;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                VendorRepository vendorRepository,
                                IndentRepository indentRepository,
                                FileStorageService fileStorageService,
                                TenantEntityHelper tenantEntityHelper,
                                EmployeeRepository employeeRepository,
                                ProjectRepository projectRepository,
                                PurchaseOrderItemRepository purchaseOrderItemRepository,
                                MaterialRepository materialRepository,
                                IndentItemRepository indentItemRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.vendorRepository = vendorRepository;
        this.indentRepository = indentRepository;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.materialRepository = materialRepository;
        this.indentItemRepository = indentItemRepository;
    }

    @Transactional
    public PurchaseOrderDto createPurchaseOrder(PurchaseOrderCreationDto creationDto) {
        if (purchaseOrderRepository.existsByPoNumberAndOrganization_Id(creationDto.getPoNumber(),TenantContext.getCurrentOrgId())) {
            throw new DuplicateResourceException("Purchase Order with PO number " + creationDto.getPoNumber() + " already exists");
        }

        Vendor vendor = vendorRepository.findByIdAndOrganization_Id(creationDto.getVendorId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor with ID " + creationDto.getVendorId() + " was not found in this organization"));

        Employee createdBy = employeeRepository.findByIdAndOrganizationId(creationDto.getCreatedBy(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + creationDto.getCreatedBy() + " was not found in this organization"));

        Project project = projectRepository.findByIdAndOrganization_Id(creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + creationDto.getProjectId() + " was not found in this organization"));

        Indent indent = null;
        if (creationDto.getIndentId() != null) {
            indent = indentRepository.findByIdAndOrganization_Id(creationDto.getIndentId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Indent with ID " + creationDto.getIndentId() + " was not found in this organization"));
        }

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setPoNumber(creationDto.getPoNumber());
        purchaseOrder.setVendor(vendor);
        purchaseOrder.setIndent(indent);
        purchaseOrder.setStatus(PurchaseOrderStatus.valueOf(creationDto.getStatus()));
        purchaseOrder.setCreatedBy(createdBy);
        purchaseOrder.setProject(project);
        purchaseOrder.setExpectedDeliveryDate(creationDto.getExpectedDeliveryDate());
        purchaseOrder.setRemarks(creationDto.getRemarks());
        purchaseOrder.setTotalAmount(BigDecimal.ZERO);
        purchaseOrder.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        // Add nested purchase order items if provided
        if (creationDto.getItems() != null) {
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (PurchaseOrderItemCreationDto itemDto : creationDto.getItems()) {
                PurchaseOrderItem item = mapToPurchaseOrderItemEntity(itemDto);
                item.setOrganization(purchaseOrder.getOrganization());
                purchaseOrder.addItem(item);
                if (item.getTotalPrice() != null) {
                    totalAmount = totalAmount.add(item.getTotalPrice());
                }
            }
            purchaseOrder.setTotalAmount(totalAmount);
        }

        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);
        return PurchaseOrderDtoConvertor.convertToDto(purchaseOrder, fileStorageService);
    }

    @Transactional
    public void recalculateTotalAmount(Long purchaseOrderId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(purchaseOrderId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + purchaseOrderId + " was not found in this organization"));

        BigDecimal totalAmount = purchaseOrderItemRepository.sumTotalPriceByPurchaseOrderId(purchaseOrderId);
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }

        purchaseOrder.setTotalAmount(totalAmount);
        purchaseOrderRepository.save(purchaseOrder);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDto getPurchaseOrderById(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + id + " was not found in this organization"));
        return PurchaseOrderDtoConvertor.convertToDto(purchaseOrder, fileStorageService);
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getAllPurchaseOrders() {
        return purchaseOrderRepository.findAll().stream()
                .map(po -> PurchaseOrderDtoConvertor.convertToDto(po, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrderDto> getAllPurchaseOrders(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return purchaseOrderRepository.findAll(pageable)
                .map(po -> PurchaseOrderDtoConvertor.convertToDto(po, fileStorageService));
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByVendor(Long vendorId) {
        return purchaseOrderRepository.findByVendorId(vendorId).stream()
                .map(po -> PurchaseOrderDtoConvertor.convertToDto(po, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByIndent(Long indentId) {
        return purchaseOrderRepository.findByIndentId(indentId).stream()
                .map(po -> PurchaseOrderDtoConvertor.convertToDto(po, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByStatus(PurchaseOrderStatus status) {
        return purchaseOrderRepository.findByStatus(status).stream()
                .map(po -> PurchaseOrderDtoConvertor.convertToDto(po, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional
    public PurchaseOrderDto updatePurchaseOrder(PurchaseOrderUpdateDto updateDto) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(updateDto.getId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + updateDto.getId() + " was not found in this organization"));

        if (updateDto.getStatus() != null) {
            purchaseOrder.setStatus(PurchaseOrderStatus.valueOf(updateDto.getStatus()));
        }

        if (updateDto.getExpectedDeliveryDate() != null) {
            purchaseOrder.setExpectedDeliveryDate(updateDto.getExpectedDeliveryDate());
        }

        if (updateDto.getRemarks() != null) {
            purchaseOrder.setRemarks(updateDto.getRemarks());
        }

        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);
        return PurchaseOrderDtoConvertor.convertToDto(purchaseOrder, fileStorageService);
    }

    @Transactional
    public void updatePurchaseOrderStatus(Long id, PurchaseOrderStatus status) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + id + " was not found in this organization"));

        purchaseOrder.setStatus(status);
        purchaseOrderRepository.save(purchaseOrder);
    }

    // ==================== Helper Methods ====================

    private PurchaseOrderItem mapToPurchaseOrderItemEntity(PurchaseOrderItemCreationDto dto) {
        Material material = materialRepository.findByIdAndOrganization_Id(dto.getMaterialId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + dto.getMaterialId() + " was not found in this organization"));

        IndentItem indentItem = null;
        if (dto.getIndentItemId() != null) {
            indentItem = indentItemRepository.findByIdAndOrganization_Id(dto.getIndentItemId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + dto.getIndentItemId() + " was not found in this organization"));
            indentItem.setConvertedToPurchaseOrder(true);
            indentItemRepository.save(indentItem);
        }

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setMaterial(material);
        item.setIndentItem(indentItem);
        item.setOrderedQuantity(dto.getOrderedQuantity());
        item.setReceivedQuantity(0);
        item.setUnitPrice(dto.getUnitPrice());

        BigDecimal unitPrice = dto.getUnitPrice() != null ? dto.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal totalPrice = unitPrice.multiply(new BigDecimal(dto.getOrderedQuantity()));
        item.setTotalPrice(totalPrice);

        item.setRemarks(dto.getRemarks());
        return item;
    }
}
