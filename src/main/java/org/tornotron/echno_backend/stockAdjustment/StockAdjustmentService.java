package org.tornotron.echno_backend.stockAdjustment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
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
import org.tornotron.echno_backend.storageLocation.StorageLocationScope;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stock-adjustment documents: draft, browse, edit, and post to the stock ledger.
 *
 * <p>A stock adjustment is the only way to set or correct a balance once a material
 * exists. Everything else that moves stock (a goods receipt, a consumption, a site
 * transfer) records a business event, so a balance that is simply wrong, or a
 * (material, project, location) row that no receipt ever created, had no route back to
 * the truth. It is deliberately not a direct edit of {@code CurrentStock}: {@link #approve}
 * writes an {@link InventoryTransaction} carrying the signed movement and its reason and
 * then moves the balance, so the resulting figure stays explainable from the ledger like
 * every other movement.
 *
 * <p>{@link #create} and {@link #update} persist the document only. Posting happens once,
 * through {@link #approve}, and a posted document is then frozen: edits and deletes are
 * refused, because changing the lines afterwards would leave the ledger describing a
 * document that no longer exists.
 */
@Service
public class StockAdjustmentService {

    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockAdjustmentMapper stockAdjustmentMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final MaterialRepository materialRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ProjectRepository projectRepository;
    private final InventoryService inventoryService;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final UserContextService userContextService;

    public StockAdjustmentService(StockAdjustmentRepository stockAdjustmentRepository,
                                  StockAdjustmentMapper stockAdjustmentMapper,
                                  TenantEntityHelper tenantEntityHelper,
                                  MaterialRepository materialRepository,
                                  StorageLocationRepository storageLocationRepository,
                                  ProjectRepository projectRepository,
                                  InventoryService inventoryService,
                                  InventoryTransactionRepository inventoryTransactionRepository,
                                  UserContextService userContextService) {
        this.stockAdjustmentRepository = stockAdjustmentRepository;
        this.stockAdjustmentMapper = stockAdjustmentMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.materialRepository = materialRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.projectRepository = projectRepository;
        this.inventoryService = inventoryService;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.userContextService = userContextService;
    }

    /** Status a document carries once its movements are on the ledger. */
    static final String POSTED_STATUS = "processed";

    /**
     * Below this, a line's movement is treated as none at all. A count matching the balance
     * to the last decimal place can still leave floating-point residue, and a ledger entry
     * for a movement of 1e-16 is a phantom row claiming stock moved when it did not.
     */
    private static final double NO_MOVEMENT = 1e-9;

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

    /**
     * Replaces the header fields and line items of a document that has not been posted.
     *
     * @param id The document to update.
     * @param creationDto The replacement header and lines.
     * @return The updated document as a DTO.
     * @throws ResourceNotFoundException if the document, or anything it references, is not in this organization.
     * @throws InvalidRequestException if the document has already been posted to the stock ledger.
     */
    @Transactional
    public StockAdjustmentDto update(Long id, StockAdjustmentCreationDto creationDto) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        requireNotPosted(stockAdjustment, "edited");
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

    /**
     * Deletes a document that has not been posted.
     *
     * @param id The document to delete.
     * @throws ResourceNotFoundException if no such document exists in this organization.
     * @throws InvalidRequestException if the document has already been posted to the stock ledger.
     */
    @Transactional
    public void delete(Long id) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        requireNotPosted(stockAdjustment, "deleted");
        stockAdjustmentRepository.delete(stockAdjustment);
    }

    /**
     * Approves a stock adjustment and posts its lines to the stock ledger.
     *
     * <p>This is the controlled way to set or correct a balance. Each line resolves the
     * balance row it applies to, from the line's own storage location or the document's,
     * and the movement it needs to reach the counted figure. A line carrying a physical
     * count is posted as the difference between that count and the balance <em>as it stands
     * now</em>, not the {@code systemQuantity} recorded when the count sheet was written, so
     * the balance ends at exactly the counted figure even if stock moved in between; the
     * line is rewritten with the figures actually posted. A line with no count falls back to
     * its signed {@code adjustmentQuantity}. Lines that come out at no movement are skipped
     * rather than writing a ledger row that changes nothing.
     *
     * <p>Every posted line writes an {@code ADJUST} {@link InventoryTransaction} whose
     * remarks carry the reason, so the balance stays explainable, and only then moves the
     * balance. Both happen in this transaction: an approval that recorded no movement is the
     * failure this whole path exists to remove. Posting is refused a second time, and the
     * document is frozen against edits afterwards.
     *
     * @param id The document to approve and post.
     * @return The posted document as a DTO.
     * @throws ResourceNotFoundException if no such document exists in this organization.
     * @throws InvalidRequestException if the document is already posted, names no project, has no lines, or a line is missing a material, a reason, or a quantity, or would drive a balance negative.
     */
    @Transactional
    public StockAdjustmentDto approve(Long id) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        requireNotPosted(stockAdjustment, "posted again");

        Project project = stockAdjustment.getProject();
        if (project == null) {
            throw new InvalidRequestException("Stock adjustment with ID " + id
                    + " names no project, so there is no balance for it to correct");
        }
        if (stockAdjustment.getLineItems().isEmpty()) {
            throw new InvalidRequestException("Stock adjustment with ID " + id
                    + " has no line items, so there is nothing to post");
        }

        Organization organization = stockAdjustment.getOrganization();
        LocalDateTime postedAt = LocalDateTime.now();
        LocalDateTime transactionDate = stockAdjustment.getEffectiveDate() != null
                ? stockAdjustment.getEffectiveDate().atStartOfDay()
                : postedAt;
        String referenceNumber = referenceNumber(stockAdjustment);
        double totalVariance = 0.0;

        for (StockAdjustmentLineItem line : stockAdjustment.getLineItems()) {
            Material material = line.getMaterial();
            if (material == null) {
                throw new InvalidRequestException("Every line on stock adjustment with ID " + id
                        + " must name the material it adjusts");
            }
            String reason = resolveReason(line, stockAdjustment, material);

            StorageLocation location = line.getLocation() != null
                    ? line.getLocation()
                    : stockAdjustment.getLocation();
            StorageLocationScope.requireUsableFromProject(location, project.getId());

            Double balance = (location != null
                    ? inventoryService.findStockAtLocation(material.getId(), project.getId(), location.getId())
                    : inventoryService.findUnlocatedStock(material.getId(), project.getId()))
                    .orElse(0.0);
            Double movement = resolveMovement(line, balance, material, id);

            // Record what was actually posted, so the document and the ledger agree.
            line.setSystemQuantity(balance);
            line.setAdjustmentQuantity(movement);

            if (Math.abs(movement) < NO_MOVEMENT) {
                line.setAdjustmentQuantity(0.0);
                continue;
            }
            double closing = balance + movement;
            if (closing < 0) {
                throw new InvalidRequestException(String.format(
                        "Adjusting material ID %d by %.2f would leave %.2f on hand at project ID %d. "
                                + "An adjustment corrects a balance to a counted figure and cannot take it below zero.",
                        material.getId(), movement, closing, project.getId()));
            }

            BigDecimal unitCost = line.getUnitValue() != null
                    ? line.getUnitValue()
                    : inventoryService.getAverageCost(material.getId(), project.getId(),
                            location != null ? location.getId() : null);

            InventoryTransaction transaction = new InventoryTransaction();
            transaction.setTransactionDate(transactionDate);
            transaction.setMaterial(material);
            transaction.setOpeningStock(balance);
            transaction.setQuantityChanged(movement);
            transaction.setClosingStock(closing);
            transaction.setTransactionType(InventoryTransactionType.ADJUST);
            transaction.setReferenceNumber(referenceNumber);
            transaction.setRemarks("Stock adjustment - " + reason);
            transaction.setProject(project);
            transaction.setStorageLocation(location);
            transaction.setOrganization(organization);
            transaction.setUnitCost(unitCost);
            inventoryTransactionRepository.save(transaction);

            inventoryService.updateCurrentStock(material, project, location, organization,
                    movement, movement > 0 ? unitCost : null);

            totalVariance += movement;
        }

        Long approver = userContextService.getCurrentUserId();
        stockAdjustment.setTotalVarianceQuantity(totalVariance);
        stockAdjustment.setStatus(POSTED_STATUS);
        stockAdjustment.setApprovedBy(approver);
        stockAdjustment.setApprovedAt(postedAt);
        stockAdjustment.setProcessedBy(approver);
        stockAdjustment.setProcessedAt(postedAt);

        StockAdjustment saved = stockAdjustmentRepository.saveAndFlush(stockAdjustment);
        return stockAdjustmentMapper.toDto(saved);
    }

    /** Refuses to change a document whose movements are already on the ledger. */
    private void requireNotPosted(StockAdjustment stockAdjustment, String action) {
        if (stockAdjustment.getProcessedAt() != null) {
            throw new InvalidRequestException("Stock adjustment with ID " + stockAdjustment.getId()
                    + " was posted to the stock ledger on " + stockAdjustment.getProcessedAt()
                    + " and cannot be " + action + ". Raise a further adjustment to correct it.");
        }
    }

    /**
     * The reason recorded against the ledger entry. A stock movement with no stated reason
     * is the thing that makes a balance unexplainable, so one is required: the line's own
     * reason, else the document's primary reason, else the request is refused.
     */
    private String resolveReason(StockAdjustmentLineItem line, StockAdjustment stockAdjustment, Material material) {
        String reason = hasText(line.getReason()) ? line.getReason() : stockAdjustment.getPrimaryReason();
        if (!hasText(reason)) {
            throw new InvalidRequestException("The line adjusting material ID " + material.getId()
                    + " has no reason, and stock adjustment with ID " + stockAdjustment.getId()
                    + " gives no primary reason to fall back on. Every stock movement must say why it happened.");
        }
        String detail = hasText(line.getReasonDetails()) ? ": " + line.getReasonDetails() : "";
        return reason + detail;
    }

    /** The signed movement a line posts: to the counted figure where there is one, else the stated delta. */
    private Double resolveMovement(StockAdjustmentLineItem line, Double balance, Material material, Long id) {
        if (line.getPhysicalQuantity() != null) {
            return line.getPhysicalQuantity() - balance;
        }
        if (line.getAdjustmentQuantity() != null) {
            return line.getAdjustmentQuantity();
        }
        throw new InvalidRequestException("The line adjusting material ID " + material.getId()
                + " on stock adjustment with ID " + id + " carries neither a counted physical quantity "
                + "nor an adjustment quantity, so there is nothing to post");
    }

    /** The ledger reference for the document, falling back to its id when it carries no number. */
    private String referenceNumber(StockAdjustment stockAdjustment) {
        return hasText(stockAdjustment.getAdjustmentNumber())
                ? stockAdjustment.getAdjustmentNumber()
                : "SA-" + stockAdjustment.getId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
