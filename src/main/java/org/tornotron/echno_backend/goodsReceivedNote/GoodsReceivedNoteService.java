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
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
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

/**
 * Create, update, and query Goods Received Notes recording deliveries against purchase orders.
 *
 * <p>Creating a GRN validates every referenced entity (vendor, receiver, project,
 * purchase order, storage location, and each line's material) against the current tenant,
 * persists the note and its line items, then publishes a {@link GrnCreatedEvent} so the
 * inventory ledger raises stock for the received materials in the same transaction.
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
                                    TransactionRetryTemplate retryTemplate) {
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

        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(creationDto.getPurchaseOrderId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + creationDto.getPurchaseOrderId() + " was not found in this organization"));

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

    /**
     * Retrieves a single GRN by its id within the current tenant.
     *
     * @param id The id of the GRN to retrieve.
     * @return The GRN as a DTO.
     * @throws ResourceNotFoundException if no GRN with the given id exists in this organization.
     */
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
        return goodsReceivedNoteRepository.findAll(pageable)
                .map(grn -> goodsReceivedNoteMapper.toDto(grn));
    }

    /**
     * Lists all GRNs recorded against a given vendor.
     *
     * @param vendorId The vendor whose GRNs to return.
     * @return The matching GRNs as DTOs.
     */
    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getGrnsByVendor(Long vendorId) {
        return goodsReceivedNoteRepository.findByVendorId(vendorId).stream()
                .map(grn -> goodsReceivedNoteMapper.toDto(grn))
                .collect(Collectors.toList());
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
        return goodsReceivedNoteRepository.findByReceivedOnBetween(startDate, endDate).stream()
                .map(grn -> goodsReceivedNoteMapper.toDto(grn))
                .collect(Collectors.toList());
    }
}
