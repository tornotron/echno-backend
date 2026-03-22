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
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderCreationDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderUpdateDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
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

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                VendorRepository vendorRepository,
                                IndentRepository indentRepository,
                                FileStorageService fileStorageService,
                                TenantEntityHelper tenantEntityHelper,
                                EmployeeRepository employeeRepository,
                                ProjectRepository projectRepository,
                                PurchaseOrderItemRepository purchaseOrderItemRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.vendorRepository = vendorRepository;
        this.indentRepository = indentRepository;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
    }

    @Transactional
    public PurchaseOrderDto createPurchaseOrder(PurchaseOrderCreationDto creationDto) {
        if (purchaseOrderRepository.existsByPoNumber(creationDto.getPoNumber())) {
            throw new DuplicateResourceException("Purchase Order with PO number " + creationDto.getPoNumber() + " already exists");
        }

        Vendor vendor = vendorRepository.findById(creationDto.getVendorId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + creationDto.getVendorId()));

        Employee createdBy = employeeRepository.findByIdAndOrganizationId(creationDto.getCreatedBy(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + creationDto.getCreatedBy()));

        Project project = projectRepository.findByIdAndOrganization_Id(creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + creationDto.getProjectId()));

        Indent indent = null;
        if (creationDto.getIndentId() != null) {
            indent = indentRepository.findById(creationDto.getIndentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Indent not found with id: " + creationDto.getIndentId()));
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

        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);
        return PurchaseOrderDtoConvertor.convertToDto(purchaseOrder, fileStorageService);
    }

    @Transactional
    public void recalculateTotalAmount(Long purchaseOrderId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + purchaseOrderId));

        BigDecimal totalAmount = purchaseOrderItemRepository.sumTotalPriceByPurchaseOrderId(purchaseOrderId);
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }

        purchaseOrder.setTotalAmount(totalAmount);
        purchaseOrderRepository.save(purchaseOrder);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDto getPurchaseOrderById(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + id));
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
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(updateDto.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + updateDto.getId()));

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
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + id));

        purchaseOrder.setStatus(status);
        purchaseOrderRepository.save(purchaseOrder);
    }
}
