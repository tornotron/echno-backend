package org.tornotron.echno_backend.goodsReceivedNote;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.goodsReceivedNote.mapper.GoodsReceivedNoteMapper;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
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
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderReceiptReconciler;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
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

/**
 * Create, update, and query Goods Received Notes recording deliveries against purchase orders.
 *
 * <p>Creating a GRN validates every referenced entity (vendor, receiver, project,
 * purchase order, storage location, and each line's material) against the current tenant,
 * persists the note and its line items, then publishes a {@link GrnCreatedEvent} so the
 * inventory ledger raises stock for the received materials in the same transaction.
 *
 * <p>The receipt is also posted back onto the purchase order it cites, by
 * {@link PurchaseOrderReceiptReconciler}: the received quantities are added to the order's
 * lines, an over-receipt is refused unless the payload acknowledged it, and the order's status
 * follows from the arithmetic. That runs inside the create transaction and before the stock
 * event is published, so an order that refuses a delivery refuses it before any stock moves.
 */
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
    private final DocumentNumberAllocator documentNumberAllocator;
    private final TransactionRetryTemplate retryTemplate;
    private final PurchaseOrderReceiptReconciler purchaseOrderReceiptReconciler;
    private final GoodsReceivedNoteAccess grnAccess;

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
                                    StorageLocationRepository storageLocationRepository,
                                    PurchaseOrderRepository purchaseOrderRepository,
                                    DocumentNumberAllocator documentNumberAllocator,
                                    TransactionRetryTemplate retryTemplate,
                                    PurchaseOrderReceiptReconciler purchaseOrderReceiptReconciler,
                                    GoodsReceivedNoteAccess grnAccess) {
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
        this.documentNumberAllocator = documentNumberAllocator;
        this.retryTemplate = retryTemplate;
        this.purchaseOrderReceiptReconciler = purchaseOrderReceiptReconciler;
        this.grnAccess = grnAccess;
    }

    /**
     * Creates a Goods Received Note with its line items and triggers the stock increase.
     *
     * <p>Allocates the GRN number, resolves the vendor, receiving employee, project, and
     * purchase order (plus an optional storage location) within the current tenant, and
     * resolves each line's material. After saving, a {@link GrnCreatedEvent} is published so
     * inventory is updated for the received goods.
     *
     * <p>The transaction is restarted on a serialization abort, and also on a unique
     * violation: the counter behind the GRN number is the row two concurrent creates contend
     * on, and a fresh attempt allocates the next number rather than reporting a collision the
     * user did not cause. The inventory listener runs after commit, so only the attempt that
     * commits raises stock.
     *
     * @param creationDto The GRN header fields and the list of received line items.
     * @return The created GRN as a DTO.
     * @throws ResourceNotFoundException if any referenced vendor, employee, project, purchase order, storage location, or material is not found in this organization.
     * @throws org.tornotron.echno_backend.common.exception.InvalidRequestException if a line would
     *     take a material past the quantity the purchase order asked for and the payload did not
     *     set {@code allowOverReceipt}.
     */
    public GoodsReceivedNoteDto createGoodsReceivedNote(GoodsReceivedNoteCreationDto creationDto) {
        return retryTemplate.execute(
                "GoodsReceivedNoteService.createGoodsReceivedNote",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION),
                () -> createGoodsReceivedNoteInTransaction(creationDto));
    }

    private GoodsReceivedNoteDto createGoodsReceivedNoteInTransaction(GoodsReceivedNoteCreationDto creationDto) {
        // Validate vendor
        Vendor vendor = vendorRepository.findByIdAndOrganization_Id(creationDto.getVendorId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor with ID " + creationDto.getVendorId() + " was not found in this organization"));

        Employee receivedBy = employeeRepository.findByIdAndOrganizationId(creationDto.getReceivedByEmployeeId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + creationDto.getReceivedByEmployeeId() + " was not found in this organization"));

        // Validate project
        Project project = projectRepository.findByIdAndOrganization_Id(creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + creationDto.getProjectId() + " was not found in this organization"));

        // Checked on the receipt as it will be recorded, before a number is allocated for it, so
        // nobody records one on a project or from a vendor they may not.
        grnAccess.require(AccessPermission.CREATE, GoodsReceivedNoteAccess.facts(
                TenantContext.getCurrentOrgId(), project.getId(), receivedBy.getId(), vendor.getId()));

        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(creationDto.getPurchaseOrderId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + creationDto.getPurchaseOrderId() + " was not found in this organization"));
        if (purchaseOrder.getStatus() == PurchaseOrderStatus.REVERSED) {
            throw new InvalidRequestException("Purchase order " + purchaseOrder.getPoNumber()
                    + " has been reversed under reversal request #" + purchaseOrder.getReversalId()
                    + ", so nothing can be received against it.");
        }

        // Create GRN
        GoodsReceivedNote grn = new GoodsReceivedNote();
        grn.setGrnNumber(
                documentNumberAllocator.allocate(DocumentNumberType.GOODS_RECEIVED_NOTE, TenantContext.getCurrentOrgId()));
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

        // Post the receipt back onto the order it cites. This runs before the stock event so a
        // refused over-receipt takes the whole note with it and no stock is raised for a
        // delivery the order would not accept. The reconciler may correct a line's ordered
        // quantity from the order, so the lines are saved again after it.
        PurchaseOrderReceiptReconciler.ReceiptOutcome outcome = purchaseOrderReceiptReconciler.applyReceipt(
                purchaseOrder, grn, items, Boolean.TRUE.equals(creationDto.getAllowOverReceipt()));
        if (outcome.matchedLines() > 0) {
            grnItemRepository.saveAll(items);
        }
        if (outcome.overReceipt()) {
            grn.setOverReceiptAcknowledged(true);
            grn = goodsReceivedNoteRepository.save(grn);
            grn.setItems(items);
        }

        // Publish GrnCreatedEvent for automatic inventory update
        eventPublisher.publishEvent(new GrnCreatedEvent(this, grn));

        return goodsReceivedNoteMapper.toDto(grn);
    }

    /**
     * Applies a partial update to an existing GRN header.
     *
     * <p>Only the non-null fields on the update DTO are changed. The line items and the
     * stock already posted for this GRN are left untouched.
     *
     * @param updateDto The GRN id and the header fields to change.
     * @return The updated GRN as a DTO.
     * @throws ResourceNotFoundException if the GRN, or a referenced employee or storage location, is not found in this organization.
     */
    @Transactional
    public GoodsReceivedNoteDto updateGoodsReceivedNote(GoodsReceivedNoteUpdateDto updateDto) {
        GoodsReceivedNote grn = requireGrn(updateDto.getId(), AccessPermission.EDIT);

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

        // An edit can name another receiver, and must not move the receipt out of what the caller
        // may edit.
        grnAccess.require(AccessPermission.EDIT, GoodsReceivedNoteAccess.facts(grn));

        grn = goodsReceivedNoteRepository.save(grn);
        return goodsReceivedNoteMapper.toDto(grn);
    }

    /**
     * Retrieves a single GRN by its id within the current tenant.
     *
     * @param id The id of the GRN to retrieve.
     * @return The GRN as a DTO.
     * @throws ResourceNotFoundException if no GRN with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public GoodsReceivedNoteDto getGrnById(Long id) {
        return goodsReceivedNoteMapper.toDto(requireGrn(id, AccessPermission.READ));
    }


    /**
     * Retrieves GRNs one page at a time, newest received first.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of GRNs per page.
     * @return A page of GRN DTOs ordered by received date descending.
     */
    @Transactional(readOnly = true)
    public Page<GoodsReceivedNoteDto> getAllGrns(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "receivedOn"));
        AccessFilter visible = grnAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<GoodsReceivedNote> grns = visible.everything()
                ? goodsReceivedNoteRepository.findAll(pageable)
                : goodsReceivedNoteRepository.findAll(GoodsReceivedNoteAccess.admittedBy(visible), pageable);
        return grns.map(grn -> goodsReceivedNoteMapper.toDto(grn));
    }

    /**
     * Lists all GRNs recorded against a given vendor.
     *
     * @param vendorId The vendor whose GRNs to return.
     * @return The matching GRNs as DTOs.
     */
    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getGrnsByVendor(Long vendorId) {
        AccessFilter visible = grnAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return List.of();
        }
        List<GoodsReceivedNote> grns = visible.everything()
                ? goodsReceivedNoteRepository.findByVendorId(vendorId)
                : goodsReceivedNoteRepository.findAll(
                        fromVendor(vendorId).and(GoodsReceivedNoteAccess.admittedBy(visible)));
        return grns.stream()
                .map(grn -> goodsReceivedNoteMapper.toDto(grn))
                .collect(Collectors.toList());
    }

    /**
     * One page of the same rows, latest receipt first, with the id as tie-break.
     *
     * @param pageNo   Zero-based page index.
     * @param pageSize Rows per page.
     * @return The requested page.
     */
    @Transactional(readOnly = true)
    public Page<GoodsReceivedNoteDto> getGrnsByVendor(Long vendorId, int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize,
                Sort.by(Sort.Direction.DESC, "receivedOn").and(Sort.by(Sort.Direction.DESC, "id")));
        AccessFilter visible = grnAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<GoodsReceivedNote> grns = visible.everything()
                ? goodsReceivedNoteRepository.findByVendorId(vendorId, pageable)
                : goodsReceivedNoteRepository.findAll(
                        fromVendor(vendorId).and(GoodsReceivedNoteAccess.admittedBy(visible)), pageable);
        return grns.map(grn -> goodsReceivedNoteMapper.toDto(grn));
    }

    /**
     * Lists GRNs received within an inclusive date range.
     *
     * @param startDate Start of the received-on range.
     * @param endDate End of the received-on range.
     * @return The matching GRNs as DTOs.
     */
    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getGrnsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        AccessFilter visible = grnAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return List.of();
        }
        List<GoodsReceivedNote> grns = visible.everything()
                ? goodsReceivedNoteRepository.findByReceivedOnBetween(startDate, endDate)
                : goodsReceivedNoteRepository.findAll(
                        receivedBetween(startDate, endDate).and(GoodsReceivedNoteAccess.admittedBy(visible)));
        return grns.stream()
                .map(grn -> goodsReceivedNoteMapper.toDto(grn))
                .collect(Collectors.toList());
    }

    /**
     * One page of the same rows, latest receipt first, with the id as tie-break.
     *
     * @param pageNo   Zero-based page index.
     * @param pageSize Rows per page.
     * @return The requested page.
     */
    @Transactional(readOnly = true)
    public Page<GoodsReceivedNoteDto> getGrnsByDateRange(LocalDateTime startDate, LocalDateTime endDate, int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize,
                Sort.by(Sort.Direction.DESC, "receivedOn").and(Sort.by(Sort.Direction.DESC, "id")));
        AccessFilter visible = grnAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<GoodsReceivedNote> grns = visible.everything()
                ? goodsReceivedNoteRepository.findByReceivedOnBetween(startDate, endDate, pageable)
                : goodsReceivedNoteRepository.findAll(
                        receivedBetween(startDate, endDate).and(GoodsReceivedNoteAccess.admittedBy(visible)), pageable);
        return grns.map(grn -> goodsReceivedNoteMapper.toDto(grn));
    }

    /**
     * The receipt, when the caller may read it and act on it this way. One the caller may not read
     * is reported missing, as one in another organization is.
     */
    private GoodsReceivedNote requireGrn(Long id, String action) {
        GoodsReceivedNote grn = goodsReceivedNoteRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> notFound(id));
        RecordFacts facts = GoodsReceivedNoteAccess.facts(grn);
        if (!grnAccess.allows(AccessPermission.READ, facts)) {
            throw notFound(id);
        }
        if (!AccessPermission.READ.equals(action)) {
            grnAccess.require(action, facts);
        }
        return grn;
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("GRN with ID " + id + " was not found in this organization");
    }

    private static Specification<GoodsReceivedNote> fromVendor(Long vendorId) {
        return (root, query, cb) -> cb.equal(root.get("vendor").get("id"), vendorId);
    }

    private static Specification<GoodsReceivedNote> receivedBetween(LocalDateTime startDate, LocalDateTime endDate) {
        return (root, query, cb) -> cb.between(root.get("receivedOn"), startDate, endDate);
    }
}
