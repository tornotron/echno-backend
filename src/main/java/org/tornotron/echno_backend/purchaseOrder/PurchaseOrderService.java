package org.tornotron.echno_backend.purchaseOrder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.purchaseOrder.mapper.PurchaseOrderMapper;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
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
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CRUD, status changes, and total maintenance for purchase orders.
 *
 * <p>Creating a purchase order allocates its PO number, validates the vendor, creator,
 * project, and optional indent, builds its line items, and sums their line totals into the
 * order total. When an item is raised from an indent item, that indent item is flagged as
 * converted to a purchase order. The total can be recomputed from the persisted lines after
 * they change.
 *
 * <p>An order is always created in {@link PurchaseOrderStatus#DRAFT}. Approving it, sending it
 * to the vendor and every later state change go through the status endpoint, so that approval
 * is a separate act on an order that already exists rather than a value the create form can
 * choose for itself.
 */
@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final VendorRepository vendorRepository;
    private final IndentRepository indentRepository;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final MaterialRepository materialRepository;
    private final IndentItemRepository indentItemRepository;
    private final DocumentNumberAllocator documentNumberAllocator;
    private final TransactionRetryTemplate retryTemplate;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                VendorRepository vendorRepository,
                                IndentRepository indentRepository,
                                PurchaseOrderMapper purchaseOrderMapper,
                                TenantEntityHelper tenantEntityHelper,
                                EmployeeRepository employeeRepository,
                                ProjectRepository projectRepository,
                                PurchaseOrderItemRepository purchaseOrderItemRepository,
                                MaterialRepository materialRepository,
                                IndentItemRepository indentItemRepository,
                                DocumentNumberAllocator documentNumberAllocator,
                                TransactionRetryTemplate retryTemplate) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.vendorRepository = vendorRepository;
        this.indentRepository = indentRepository;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.materialRepository = materialRepository;
        this.indentItemRepository = indentItemRepository;
        this.documentNumberAllocator = documentNumberAllocator;
        this.retryTemplate = retryTemplate;
    }

    /**
     * Creates a purchase order with its line items and computed total.
     *
     * <p>Allocates the PO number and resolves the vendor, creator, project, and optional
     * indent. Each supplied item is built, its line total added to the order total, and any
     * linked indent item is marked as converted to a purchase order.
     *
     * <p>The transaction is restarted on a serialization abort, and also on a unique
     * violation: the counter behind the PO number is the row two concurrent creates contend
     * on, and a fresh attempt allocates the next number rather than reporting a collision the
     * user did not cause. See {@link DocumentNumberAllocator} for why that is enough.
     *
     * @param creationDto The order header fields and the list of line items.
     * @return The created purchase order as a DTO.
     * @throws InvalidRequestException if a status other than DRAFT is asked for on create.
     * @throws ResourceNotFoundException if the vendor, creator, project, indent, or a line's material or indent item is not found in this organization.
     */
    public PurchaseOrderDto createPurchaseOrder(PurchaseOrderCreationDto creationDto) {
        if (creationDto.getStatus() != null && creationDto.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new InvalidRequestException(
                    "A purchase order is created as " + PurchaseOrderStatus.DRAFT + " and cannot be "
                            + "created as " + creationDto.getStatus() + ". Create it first, then move "
                            + "it with the purchase order status endpoint.");
        }

        return retryTemplate.execute(
                "PurchaseOrderService.createPurchaseOrder",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION),
                () -> createPurchaseOrderInTransaction(creationDto));
    }

    private PurchaseOrderDto createPurchaseOrderInTransaction(PurchaseOrderCreationDto creationDto) {
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

        Organization organization = tenantEntityHelper.resolveCurrentOrganization();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setPoNumber(
                documentNumberAllocator.allocate(DocumentNumberType.PURCHASE_ORDER, TenantContext.getCurrentOrgId()));
        purchaseOrder.setVendor(vendor);
        purchaseOrder.setIndent(indent);
        purchaseOrder.setStatus(PurchaseOrderStatus.DRAFT);
        purchaseOrder.setCreatedBy(createdBy);
        purchaseOrder.setProject(project);
        purchaseOrder.setExpectedDeliveryDate(creationDto.getExpectedDeliveryDate());
        purchaseOrder.setRemarks(creationDto.getRemarks());
        purchaseOrder.setTotalAmount(BigDecimal.ZERO);
        purchaseOrder.setOrganization(organization);

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
        return purchaseOrderMapper.toDto(purchaseOrder);
    }

    /**
     * Recomputes and stores a purchase order's total from the sum of its line totals.
     *
     * <p>Treats a missing sum (no lines) as zero. Call this after line items change.
     *
     * @param purchaseOrderId The id of the purchase order to recompute.
     * @throws ResourceNotFoundException if no purchase order with the given id exists in this organization.
     */
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

    /**
     * Retrieves a single purchase order by its id within the current tenant.
     *
     * @param id The id of the purchase order to retrieve.
     * @return The purchase order as a DTO.
     * @throws ResourceNotFoundException if no purchase order with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public PurchaseOrderDto getPurchaseOrderById(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + id + " was not found in this organization"));
        return purchaseOrderMapper.toDto(purchaseOrder);
    }


    /**
     * Retrieves purchase orders one page at a time, newest first.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of purchase orders per page.
     * @return A page of purchase order DTOs ordered by creation time descending.
     */
    @Transactional(readOnly = true)
    public Page<PurchaseOrderDto> getAllPurchaseOrders(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return purchaseOrderRepository.findAll(pageable)
                .map(po -> purchaseOrderMapper.toDto(po));
    }

    /**
     * Lists purchase orders placed on a given vendor.
     *
     * @param vendorId The vendor whose purchase orders to return.
     * @return The matching purchase orders as DTOs.
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByVendor(Long vendorId) {
        return purchaseOrderRepository.findByVendorId(vendorId).stream()
                .map(po -> purchaseOrderMapper.toDto(po))
                .collect(Collectors.toList());
    }

    /**
     * Lists purchase orders raised from a given indent.
     *
     * @param indentId The indent whose purchase orders to return.
     * @return The matching purchase orders as DTOs.
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByIndent(Long indentId) {
        return purchaseOrderRepository.findByIndentId(indentId).stream()
                .map(po -> purchaseOrderMapper.toDto(po))
                .collect(Collectors.toList());
    }

    /**
     * Lists purchase orders in a given status.
     *
     * @param status The status to filter by.
     * @return The matching purchase orders as DTOs.
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByStatus(PurchaseOrderStatus status) {
        return purchaseOrderRepository.findByStatus(status).stream()
                .map(po -> purchaseOrderMapper.toDto(po))
                .collect(Collectors.toList());
    }

    /**
     * Applies a partial update to a purchase order header.
     *
     * <p>Only the non-null status, project, expected delivery date, and remarks are changed.
     * Line items are not affected, and neither is the order total: it is the sum of the lines
     * and is recomputed whenever one of them changes, so {@code totalAmount} on the payload is
     * ignored rather than written.
     *
     * @param updateDto The purchase order id and the header fields to change.
     * @return The updated purchase order as a DTO.
     * @throws ResourceNotFoundException if no purchase order with the given id exists in this
     *     organization, or the payload names a project that does not.
     */
    @Transactional
    public PurchaseOrderDto updatePurchaseOrder(PurchaseOrderUpdateDto updateDto) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(updateDto.getId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + updateDto.getId() + " was not found in this organization"));

        if (updateDto.getStatus() != null) {
            purchaseOrder.setStatus(PurchaseOrderStatus.valueOf(updateDto.getStatus()));
        }

        if (updateDto.getProjectId() != null) {
            Project project = projectRepository.findByIdAndOrganization_Id(updateDto.getProjectId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Project with ID " + updateDto.getProjectId() + " was not found in this organization"));
            purchaseOrder.setProject(project);
        }

        if (updateDto.getExpectedDeliveryDate() != null) {
            purchaseOrder.setExpectedDeliveryDate(updateDto.getExpectedDeliveryDate());
        }

        if (updateDto.getRemarks() != null) {
            purchaseOrder.setRemarks(updateDto.getRemarks());
        }

        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);
        return purchaseOrderMapper.toDto(purchaseOrder);
    }

    /**
     * Sets the status of a purchase order.
     *
     * @param id The id of the purchase order to update.
     * @param status The new status.
     * @throws ResourceNotFoundException if no purchase order with the given id exists in this organization.
     */
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
