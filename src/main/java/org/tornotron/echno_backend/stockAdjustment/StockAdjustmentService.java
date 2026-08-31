package org.tornotron.echno_backend.stockAdjustment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.approval.ApprovalParty;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
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
import org.tornotron.echno_backend.user.UserNameDirectory;
import org.tornotron.echno_backend.user.UserNameLookup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
 * <p>{@link #create} and {@link #update} persist the document only, with one thing read from
 * the stock rather than the request: each line is stamped with the balance it is being raised
 * against, by {@link #stampOpeningBalance}. Posting happens once, through {@link #approve}, and
 * a posted document is then frozen: edits and deletes are refused, because changing the lines
 * afterwards would leave the ledger describing a document that no longer exists.
 *
 * <p>Those two halves meet at {@link #requireBalanceUnmoved}: an approval is refused when the
 * balance has moved since the document was raised. The figures approved are then the figures
 * raised, which on a document whose whole purpose is to be an auditable correction is what the
 * second signature is a signature on.
 *
 * <p>A draft has one other way off the line: {@link #reject}, which records that an approver
 * looked at the correction and refused it. It moves no stock, and it is the only outcome that
 * keeps the refusal on the record. Deleting the document instead erases the fact that it was
 * ever raised and why it was turned down, which on a document whose purpose is to make a
 * balance explainable throws away half of what there was to explain. A rejected document is
 * frozen the same way a posted one is, for the same reason: the decision has been taken and
 * the record of it is the point.
 *
 * <p>Because approval is what moves the balance, it is also where segregation of duties
 * applies: {@link #create} records who raised the document from the session, and
 * {@link #approve} refuses an approval by that same person unless they hold the break-glass
 * role. See {@link SelfApprovalPolicy}. {@link #reject} is deliberately outside that rule.
 *
 * <p>Every write path here reads the document, decides on its state, and then writes, so all
 * four load it through {@link StockAdjustmentRepository#lockByIdAndOrganizationId} rather than
 * the plain lookup. Without that, two callers acting at once would each read the state as it
 * stood before the other and both pass the guard: concurrent approvals would post the same
 * movement to the ledger twice, and an approval racing a rejection would post the movement and
 * then record the document as refused. Reads that only display the document are not locked.
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
    private final SelfApprovalPolicy selfApprovalPolicy;
    private final UserNameDirectory userNameDirectory;

    public StockAdjustmentService(StockAdjustmentRepository stockAdjustmentRepository,
                                  StockAdjustmentMapper stockAdjustmentMapper,
                                  TenantEntityHelper tenantEntityHelper,
                                  MaterialRepository materialRepository,
                                  StorageLocationRepository storageLocationRepository,
                                  ProjectRepository projectRepository,
                                  InventoryService inventoryService,
                                  InventoryTransactionRepository inventoryTransactionRepository,
                                  UserContextService userContextService,
                                  SelfApprovalPolicy selfApprovalPolicy,
                                  UserNameDirectory userNameDirectory) {
        this.stockAdjustmentRepository = stockAdjustmentRepository;
        this.stockAdjustmentMapper = stockAdjustmentMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.materialRepository = materialRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.projectRepository = projectRepository;
        this.inventoryService = inventoryService;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.userContextService = userContextService;
        this.selfApprovalPolicy = selfApprovalPolicy;
        this.userNameDirectory = userNameDirectory;
    }

    /** Status a document carries once its movements are on the ledger. */
    static final String POSTED_STATUS = "processed";

    /**
     * Status a document carries once an approver has refused it. Part of the vocabulary the web
     * client already reads, alongside {@link #DRAFT_STATUS} and {@link #POSTED_STATUS}.
     */
    static final String REJECTED_STATUS = "rejected";

    /**
     * Status a document carries until it is approved, and the only one a request body may ask
     * for. Compared case-insensitively, since the status column holds the frontend's own
     * lower-case vocabulary rather than an enum.
     */
    static final String DRAFT_STATUS = "draft";

    /**
     * Below this, a line's movement is treated as none at all. A count matching the balance
     * to the last decimal place can still leave floating-point residue, and a ledger entry
     * for a movement of 1e-16 is a phantom row claiming stock moved when it did not.
     */
    private static final double NO_MOVEMENT = 1e-9;

    /**
     * Raises a stock-adjustment document as a draft, with its line items.
     *
     * <p>The document is a {@link #DRAFT_STATUS} whether the payload says so or says nothing,
     * and any other status is refused: see {@link #requireDraftStatus}. Nothing is posted here;
     * {@link #approve} is what moves the balance.
     *
     * @param creationDto The header fields and line items of the document.
     * @return The created document as a DTO.
     * @throws InvalidRequestException if the payload asks for a status other than draft.
     * @throws ResourceNotFoundException if the location, the project or a line's material is not found in this organization.
     */
    @Transactional
    public StockAdjustmentDto create(StockAdjustmentCreationDto creationDto) {
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        StockAdjustment stockAdjustment = new StockAdjustment();
        stockAdjustment.setOrganization(organization);
        applyHeaderFields(stockAdjustment, creationDto);
        // Who raised the document is taken from the session, never from the request body: it is
        // what approval is checked against, so a caller naming someone else as the raiser would
        // clear their own way to approve it.
        stockAdjustment.setSubmittedBy(userContextService.getCurrentUserId());
        stockAdjustment.setSubmittedAt(LocalDateTime.now());
        applyLineItems(stockAdjustment, creationDto.getLineItems(), organization);
        stampHeaderTotals(stockAdjustment);
        StockAdjustment saved = stockAdjustmentRepository.saveAndFlush(stockAdjustment);
        return stockAdjustmentMapper.toDto(saved, namesFor(saved));
    }

    @Transactional(readOnly = true)
    public StockAdjustmentDto getById(Long id) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        return stockAdjustmentMapper.toDto(stockAdjustment, namesFor(stockAdjustment));
    }


    @Transactional(readOnly = true)
    public Page<StockAdjustmentDto> getAll(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<StockAdjustment> adjustments = stockAdjustmentRepository.findAll(pageable);
        UserNameLookup names = namesFor(adjustments.getContent());
        return adjustments.map(adjustment -> stockAdjustmentMapper.toDto(adjustment, names));
    }

    /**
     * Replaces the header fields and line items of a document that has not been posted.
     *
     * @param id The document to update.
     * @param creationDto The replacement header and lines.
     * @return The updated document as a DTO.
     * @throws ResourceNotFoundException if the document, or anything it references, is not in this organization.
     * @throws InvalidRequestException if the document has already been posted to the stock ledger, or if the payload asks for a status other than draft.
     */
    @Transactional
    public StockAdjustmentDto update(Long id, StockAdjustmentCreationDto creationDto) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .lockByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        requireNotPosted(stockAdjustment, "edited");
        requireNotRejected(stockAdjustment, "edited");
        Organization organization = stockAdjustment.getOrganization();

        applyHeaderFields(stockAdjustment, creationDto);

        // Replace the line-item collection: clear in place (orphanRemoval deletes the old
        // rows) and re-add from the request, keeping Hibernate's collection tracking intact.
        stockAdjustment.getLineItems().clear();
        applyLineItems(stockAdjustment, creationDto.getLineItems(), organization);
        stampHeaderTotals(stockAdjustment);

        // saveAndFlush before mapping so the freshly inserted line-item ids are populated
        // on the returned DTO (documented gotcha: without the flush the child ids are null).
        StockAdjustment saved = stockAdjustmentRepository.saveAndFlush(stockAdjustment);
        return stockAdjustmentMapper.toDto(saved, namesFor(saved));
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
                .lockByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        requireNotPosted(stockAdjustment, "deleted");
        requireNotRejected(stockAdjustment, "deleted");
        stockAdjustmentRepository.delete(stockAdjustment);
    }

    /**
     * Approves a stock adjustment and posts its lines to the stock ledger.
     *
     * <p>This is the controlled way to set or correct a balance. Each line resolves the
     * balance row it applies to, from the line's own storage location or the document's, and
     * the movement it needs to reach the counted figure: a line carrying a physical count posts
     * the difference between that count and the balance, and a line with no count falls back to
     * its signed {@code adjustmentQuantity}. Lines that come out at no movement are skipped
     * rather than writing a ledger row that changes nothing.
     *
     * <p>The approval is refused when the balance has moved since the document was raised, so
     * what is posted is the arithmetic the approver read and not a recomputation of it. See
     * {@link #requireBalanceUnmoved}, which carries the reasoning; the opening figure it checks
     * against is stamped onto the line when the document is written, by
     * {@link #stampOpeningBalance}. Because the balance is then known to be the one on the line,
     * the figures written back to the line are the raised figures, and the document, the count
     * sheet and the ledger all describe one movement.
     *
     * <p>The approval is refused up front when the person approving is the one who raised the
     * document, unless they hold the break-glass role, in which case the posting is allowed and
     * the ledger entries say they were self-approved. See {@link SelfApprovalPolicy}.
     *
     * <p>The location on a line is scoped through
     * {@link StorageLocationScope#requireUsableForBalanceCorrection}, not the strict rule the
     * other stock paths use. A location belonging to another project is still refused, unless a
     * balance row already sits at that (material, project, location), which is the one pairing an
     * adjustment exists to correct: the strict rule is written for a new movement, and applying it
     * here would leave a wrongly located balance with no route back at all.
     *
     * <p>The document's two totals are restated here from what was actually posted, rather than
     * left at the sums the draft carried. They can differ by a line whose movement came out at
     * nothing, which is skipped rather than posted, and the value total can differ on any line
     * with no unit value of its own, because the cost such a line posts at is the running average
     * at the moment of posting.
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
     * @throws InvalidRequestException if the document is already posted, is being approved by whoever raised it without the break-glass role, names no project, has no lines, or a line is missing a material, a reason, or a quantity, was raised against a balance that has since moved, names a location on another project that holds no balance for it, or would drive a balance negative.
     */
    @Transactional
    public StockAdjustmentDto approve(Long id) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .lockByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        requireNotPosted(stockAdjustment, "posted again");
        requireNotRejected(stockAdjustment, "approved");

        Long approver = userContextService.getCurrentUserId();
        boolean selfApproved = selfApprovalPolicy.checkSelfApproval(
                ApprovalParty.ofUser(stockAdjustment.getSubmittedBy()),
                ApprovalParty.ofUser(approver),
                "Stock adjustment with ID " + id);

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
        BigDecimal totalValue = BigDecimal.ZERO;
        // What this approval has already posted to each balance row, so a later line on the same
        // row is measured against the balance as it stood when the approval began. See
        // requireBalanceUnmoved: a document may split one shelf's variance across several lines,
        // one per reason, and the approver read all of them.
        Map<BalanceRow, Double> postedHere = new HashMap<>();

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

            Optional<Double> existingBalance = readBalance(material, project, location);
            StorageLocationScope.requireUsableForBalanceCorrection(location, project.getId(),
                    existingBalance::isPresent);
            Double balance = existingBalance.orElse(0.0);
            BalanceRow row = new BalanceRow(material.getId(), location != null ? location.getId() : null);
            requireBalanceUnmoved(line, balance - postedHere.getOrDefault(row, 0.0), material, id);
            Double movement = resolveMovement(line, balance, material, id);

            // Record what was posted, so the document and the ledger agree. The guard above has
            // already established that this is the opening figure the document was raised
            // against, so on any line carrying one these two writes change nothing; they are
            // what fills the figures in on a line raised before the opening balance was stamped.
            line.setSystemQuantity(balance);
            line.setAdjustmentQuantity(movement);

            if (Math.abs(movement) < NO_MOVEMENT) {
                line.setAdjustmentQuantity(0.0);
                line.setTotalAdjustmentValue(atColumnScale(BigDecimal.ZERO));
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
            transaction.setRemarks("Stock adjustment - " + reason + selfApprovalNote(selfApproved));
            transaction.setProject(project);
            transaction.setStorageLocation(location);
            transaction.setOrganization(organization);
            transaction.setUnitCost(unitCost);
            inventoryTransactionRepository.save(transaction);

            inventoryService.updateCurrentStock(material, project, location, organization,
                    movement, movement > 0 ? unitCost : null);

            // What the line is worth, at the cost the ledger entry was actually written with, so
            // the document's money agrees with the movement it posted rather than with whatever
            // the draft was able to work out. On a line carrying its own unit value this is the
            // figure the draft already held; on one without, it is what the running average came
            // to at the moment of posting, which no draft could have stated.
            BigDecimal postedValue = lineValue(movement, unitCost);
            if (postedValue != null) {
                line.setTotalAdjustmentValue(postedValue);
                totalValue = totalValue.add(postedValue);
            }

            totalVariance += movement;
            postedHere.merge(row, movement, Double::sum);
        }

        stockAdjustment.setTotalVarianceQuantity(totalVariance);
        stockAdjustment.setTotalAdjustmentValue(atColumnScale(totalValue));
        stockAdjustment.setStatus(POSTED_STATUS);
        stockAdjustment.setApprovedBy(approver);
        stockAdjustment.setApprovedAt(postedAt);
        stockAdjustment.setProcessedBy(approver);
        stockAdjustment.setProcessedAt(postedAt);

        StockAdjustment saved = stockAdjustmentRepository.saveAndFlush(stockAdjustment);
        return stockAdjustmentMapper.toDto(saved, namesFor(saved));
    }

    /**
     * Rejects a stock adjustment, recording who refused it, when, and why.
     *
     * <p>This is the other way a draft leaves the pending state, and the only one that keeps the
     * refusal. Nothing is written to the stock ledger and no balance moves: the document is
     * stamped with the rejection and closed. Deleting it instead is what the module had before,
     * and it removes the fact that a correction was proposed along with the reason it was not
     * accepted, on a document that exists to make a balance explainable.
     *
     * <p>Three deliberate decisions sit behind this.
     *
     * <p><b>The reason is required.</b> Every posted line has to say why the stock moved, or
     * {@link #resolveReason} refuses it. A rejection is held to the same standard: without the
     * reason it records nothing that could not be read off the absence of an approval, and the
     * next person looking at the balance is no better off than if the draft had been deleted.
     *
     * <p><b>Self-rejection is not subject to {@link SelfApprovalPolicy}.</b> That rule is the
     * second pair of eyes on the entry an approval posts, and a rejection posts no entry. It also
     * takes nothing away from anybody: whoever raised the document can already delete it outright,
     * so refusing them the rejection would only push them towards the outcome that keeps no
     * record. This follows the same reading the attendance path settled on for withdrawing your
     * own request.
     *
     * <p><b>A posted document cannot be rejected.</b> Its lines are on the ledger and the balance
     * has moved, so a rejection would claim a correction was refused while the stock figure says
     * it happened. A posting is undone by raising a further adjustment, not by relabelling the
     * one that posted it.
     *
     * <p>Rejection is terminal. The document cannot then be edited, deleted, approved or rejected
     * again: there is one set of rejection columns, so an edit that reopened the document would
     * have to overwrite the refusal to record the next decision, and the record of the refusal is
     * the entire reason to reject rather than delete. A raiser who wants to answer the objection
     * raises a fresh draft, which costs nothing because a draft posts nothing, and the rejected
     * document stays alongside it saying what was asked for and why it was turned down.
     *
     * @param id The document to reject.
     * @param reason Why it is being refused. Required.
     * @return The rejected document as a DTO.
     * @throws ResourceNotFoundException if no such document exists in this organization.
     * @throws InvalidRequestException if the document is already posted, is already rejected, or no reason was given.
     */
    @Transactional
    public StockAdjustmentDto reject(Long id, String reason) {
        StockAdjustment stockAdjustment = stockAdjustmentRepository
                .lockByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Stock adjustment with ID " + id + " was not found in this organization"));
        requireNotPosted(stockAdjustment, "rejected");
        requireNotRejected(stockAdjustment, "rejected again");

        String statedReason = reason == null ? null : reason.trim();
        if (!hasText(statedReason)) {
            throw new InvalidRequestException("Rejecting stock adjustment with ID " + id
                    + " needs a reason. The record of a refused correction is what the rejection is "
                    + "for, and a rejection that does not say why says nothing the missing approval "
                    + "did not already say.");
        }

        stockAdjustment.setStatus(REJECTED_STATUS);
        stockAdjustment.setRejectedBy(userContextService.getCurrentUserId());
        stockAdjustment.setRejectedAt(LocalDateTime.now());
        stockAdjustment.setRejectionReason(statedReason);

        StockAdjustment saved = stockAdjustmentRepository.saveAndFlush(stockAdjustment);
        return stockAdjustmentMapper.toDto(saved, namesFor(saved));
    }

    /**
     * What a self-approved posting carries in the ledger. The document already records the raiser
     * and the approver side by side, but the ledger entry is what a stock figure is explained
     * from, so a movement nobody independent agreed to says so where the figure is read.
     */
    private String selfApprovalNote(boolean selfApproved) {
        return selfApproved ? " (self-approved: raised and approved by the same person)" : "";
    }

    /**
     * Refuses a document asked to be written in any state but {@link #DRAFT_STATUS}.
     *
     * <p>A stock adjustment has one transition, and everything this module exists for hangs off
     * it: {@link #approve} refuses an approval by whoever raised the document unless they hold
     * the break-glass role, writes an {@link InventoryTransaction} per line, moves the balance,
     * and stamps the approver and the posting time. A body that set the status itself reached
     * the posted state with none of that: no approver on record, no ledger entries, and the
     * balance untouched, while every list and detail view reads the document as dealt with. The
     * self-approval control added for that transition would be sidestepped by simply not using
     * the transition.
     *
     * <p>Applied to the update path as well as create, because the two write the same header
     * and a document can be edited right up until it is posted, so gating only create would
     * leave the same door one call further along.
     *
     * <p>This is the rule {@code ProjectService.addProject} and
     * {@code PurchaseOrderService.createPurchaseOrder} apply: a create may not reach a state
     * whose transition carries a check or an event.
     *
     * @param status The status the request body asked for, or null when it asked for none.
     * @throws InvalidRequestException if that status is anything but {@link #DRAFT_STATUS}.
     */
    private void requireDraftStatus(String status) {
        if (status != null && !status.isBlank() && !DRAFT_STATUS.equalsIgnoreCase(status.trim())) {
            throw new InvalidRequestException(
                    "A stock adjustment is raised as a " + DRAFT_STATUS + " and cannot be given the status "
                            + status + ". Post it with POST /stock-adjustments/{id}/approve, which is "
                            + "what checks who is approving it, writes the stock-ledger entries and "
                            + "moves the balance.");
        }
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
     * Refuses to change or re-decide a document an approver has already refused.
     *
     * <p>The document carries one {@code rejectedBy}, one {@code rejectedAt} and one
     * {@code rejectionReason}, so anything that reopened it would end up overwriting the refusal
     * to record whatever came next, and the refusal is the whole reason {@link #reject} exists
     * rather than a delete. A rejected document is therefore read-only, like a posted one, and
     * the correction is pursued by raising a fresh draft alongside it.
     */
    private void requireNotRejected(StockAdjustment stockAdjustment, String action) {
        if (stockAdjustment.getRejectedAt() != null) {
            throw new InvalidRequestException("Stock adjustment with ID " + stockAdjustment.getId()
                    + " was rejected on " + stockAdjustment.getRejectedAt()
                    + " and cannot be " + action + ". Raise a new adjustment for the correction, so "
                    + "the rejection and the reason given for it stay on the record.");
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

    /**
     * Identifies the balance row a line moves, within one document's project.
     *
     * <p>Several lines may share one: a count sheet can split a shelf's variance across a line
     * per reason, so much damaged and so much lost. {@link #approve} needs to tell those apart
     * from stock that moved underneath the document.
     *
     * @param materialId The material.
     * @param locationId The storage location, or null for the row held against no location.
     */
    private record BalanceRow(Long materialId, Long locationId) {
    }

    /**
     * The balance row a line applies to, empty when no such row exists.
     *
     * <p>Shared by the two places that need it and must agree: {@link #stampOpeningBalance},
     * which records the figure on the draft, and {@link #approve}, which posts against it. If
     * they read different rows the stamped opening balance would look moved on every approval.
     *
     * @param material The material the line adjusts.
     * @param project The project the document corrects a balance on.
     * @param location The line's location, else the document's, else null for the unlocated row.
     * @return The quantity on hand, or empty when the balance row does not exist.
     */
    private Optional<Double> readBalance(Material material, Project project, StorageLocation location) {
        return location != null
                ? inventoryService.findStockAtLocation(material.getId(), project.getId(), location.getId())
                : inventoryService.findUnlocatedStock(material.getId(), project.getId());
    }

    /**
     * Refuses an approval whose balance has moved since the document was raised.
     *
     * <p>This is what makes the second signature mean something. A stock adjustment is approved
     * by someone other than the person who raised it, and what they are agreeing to is an
     * arithmetic: this material stood at the opening figure on the line, the count found the
     * physical figure, so post the difference. If the balance moves in between, approving posts a
     * different arithmetic from the one on the document, and it does so silently: a line carrying
     * a count always drives the balance to exactly the counted figure, so a goods receipt booked
     * after the count is reversed by the approval and nothing anywhere says it was. The receipt
     * stays on the ledger; the stock does not.
     *
     * <p>Refusing is the only outcome that keeps the approval honest. Posting the drafted variance
     * instead would guess the other way, and it is a guess: whether goods received after a count
     * were already on the shelf when it was taken is not something the data can answer, so a
     * default either absorbs a real receipt or double-counts one. Both need a human, and this is
     * the point at which one is present.
     *
     * <p>The way forward is to save the document again, which re-reads the opening balance onto
     * every line (see {@link #stampOpeningBalance}), and approve it once the count has been
     * checked against the figure that moved. That keeps the decision with a person and leaves the
     * document saying what was agreed.
     *
     * <p>What the approval has itself already posted to the same balance row is discounted first,
     * so this measures stock that moved underneath the document and not the document's own work.
     * A count sheet may split one shelf's variance across a line per reason, and the approver read
     * all of those lines; refusing the second because the first had just moved the balance would
     * refuse a document nobody wrote wrongly.
     *
     * <p>A line carrying no opening balance is let through: it was raised before the figure was
     * stamped, so there is nothing to compare and nothing the approver can be said to have
     * disagreed with. {@link #approve} then fills the figure in from what it posts.
     *
     * @param line The line being posted.
     * @param balance The balance as it stood when this approval began.
     * @param material The material the line adjusts, named in the message.
     * @param id The document being approved, named in the message.
     * @throws InvalidRequestException if the line was raised against a different opening balance.
     */
    private void requireBalanceUnmoved(StockAdjustmentLineItem line, Double balance, Material material, Long id) {
        Double opening = line.getSystemQuantity();
        if (opening == null || Math.abs(opening - balance) < NO_MOVEMENT) {
            return;
        }
        throw new InvalidRequestException("The line adjusting material ID " + material.getId()
                + " on stock adjustment with ID " + id + " was raised against a balance of " + opening
                + ", and the balance stood at " + balance + " when this approval began. Something "
                + "moved this stock after the count sheet was written, so approving it would post a "
                + "correction nobody has agreed to and would silently reverse whatever moved it. "
                + "Check the count against the figure it now stands at, save the document to take "
                + "up the current balance, and approve it again.");
    }

    /**
     * Records the balance a line is being raised against, and the variance that follows from it.
     *
     * <p>{@code systemQuantity} is the system's own figure, not a number the person raising the
     * document gets to assert, so it is read from the stock here the same way {@code submittedBy}
     * is read from the session rather than the request body. Before this it
     * was whatever the client sent: on the web app a hand-typed box defaulting to zero, checked
     * against nothing. That left it decorative, which mattered once {@link #approve} started
     * refusing an approval whose balance has moved, because a guard against a hand-typed number
     * fires on typing mistakes rather than on stock moving.
     *
     * <p>Where the line carries a count, the variance is recomputed from the stamped figure too.
     * The three numbers on a line are one piece of arithmetic and a document showing an opening
     * balance, a count, and a difference that is not the difference between them is worse than one
     * showing no opening balance at all.
     *
     * <p>A line too incomplete to read a balance for, naming no material or sitting on a document
     * naming no project, keeps what the client sent. There is no balance to read, and such a line
     * cannot be approved anyway: {@link #approve} refuses both.
     *
     * @param item The line being written.
     * @param stockAdjustment The document it belongs to, for the project and the fallback location.
     */
    private void stampOpeningBalance(StockAdjustmentLineItem item, StockAdjustment stockAdjustment) {
        Material material = item.getMaterial();
        Project project = stockAdjustment.getProject();
        if (material == null || project == null) {
            return;
        }
        StorageLocation location = item.getLocation() != null
                ? item.getLocation()
                : stockAdjustment.getLocation();
        Double balance = readBalance(material, project, location).orElse(0.0);
        item.setSystemQuantity(balance);
        if (item.getPhysicalQuantity() != null) {
            item.setAdjustmentQuantity(item.getPhysicalQuantity() - balance);
        }
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

    /**
     * Copies the header scalars from the creation DTO, resolving the location and project.
     * Deliberately does not touch {@code submittedBy}: it is stamped from the session in
     * {@link #create} and left alone by {@link #update}, so an edit cannot move the document
     * onto a different raiser and clear the way for its own approval.
     *
     * <p>The status is not copied either. It is written here as {@link #DRAFT_STATUS} and moved
     * only by {@link #approve}; a body naming any other status is refused rather than ignored,
     * so a caller who believed they were posting a document finds out that they were not.
     *
     * <p>Neither total is copied. {@code totalVarianceQuantity} and {@code totalAdjustmentValue}
     * are arithmetic over the lines, so they are summed from the lines once those exist, by
     * {@link #stampHeaderTotals}. Copying them let a document assert a header figure that did not
     * match the sum of its own lines, which it now always did, because the lines' own figures are
     * stamped by the server and the client's header was summed from what it sent instead.
     */
    private void applyHeaderFields(StockAdjustment stockAdjustment, StockAdjustmentCreationDto dto) {
        requireDraftStatus(dto.getStatus());
        stockAdjustment.setAdjustmentNumber(dto.getAdjustmentNumber());
        stockAdjustment.setType(dto.getType());
        stockAdjustment.setStatus(DRAFT_STATUS);
        stockAdjustment.setAdjustmentDate(dto.getAdjustmentDate());
        stockAdjustment.setEffectiveDate(dto.getEffectiveDate());
        stockAdjustment.setPrimaryReason(dto.getPrimaryReason());
        stockAdjustment.setJustification(dto.getJustification());
        stockAdjustment.setPhysicalCountDate(dto.getPhysicalCountDate());
        stockAdjustment.setPhysicalCountBy(dto.getPhysicalCountBy());
        stockAdjustment.setCountMethod(dto.getCountMethod());

        stockAdjustment.setLocation(resolveLocation(dto.getLocationId()));
        stockAdjustment.setProject(resolveProject(dto.getProjectId()));
    }

    /**
     * Builds and attaches the line items, resolving each line's material and location.
     *
     * <p>Runs after {@link #applyHeaderFields}, because the opening balance each line is stamped
     * with is read against the document's project and falls back to its location.
     */
    private void applyLineItems(StockAdjustment stockAdjustment,
                                List<StockAdjustmentLineItemCreationDto> lineItemDtos,
                                Organization organization) {
        if (lineItemDtos == null) {
            return;
        }
        for (StockAdjustmentLineItemCreationDto lineDto : lineItemDtos) {
            StockAdjustmentLineItem item = new StockAdjustmentLineItem();
            item.setDescription(lineDto.getDescription());
            // Kept only as the fallback for a line no balance can be read for; where one can be,
            // stampOpeningBalance below overwrites it with the figure the system actually holds.
            item.setSystemQuantity(lineDto.getSystemQuantity());
            item.setPhysicalQuantity(lineDto.getPhysicalQuantity());
            item.setAdjustmentQuantity(lineDto.getAdjustmentQuantity());
            item.setUnit(lineDto.getUnit());
            item.setUnitValue(lineDto.getUnitValue());
            // Kept only as the fallback for a line whose value cannot be computed, for the same
            // reason systemQuantity above is; stampLineValue below overwrites it where it can.
            item.setTotalAdjustmentValue(lineDto.getTotalAdjustmentValue());
            item.setReason(lineDto.getReason());
            item.setReasonDetails(lineDto.getReasonDetails());
            item.setBinLocation(lineDto.getBinLocation());
            item.setNotes(lineDto.getNotes());
            item.setMaterial(resolveMaterial(lineDto.getMaterialId()));
            item.setLocation(resolveLocation(lineDto.getLocationId()));
            item.setOrganization(organization);
            stampOpeningBalance(item, stockAdjustment);
            stampLineValue(item);
            stockAdjustment.addLineItem(item);
        }
    }

    /**
     * Records what a line's variance is worth, from the variance the server stamped on it.
     *
     * <p>Same reasoning as {@link #stampOpeningBalance}, one step along: the value is the product
     * of two figures already on the line, so it belongs where those figures are. Before this it
     * was whatever the client sent, and once {@link #stampOpeningBalance} began overwriting the
     * variance the sent value was a product of figures the server had since replaced. A line
     * showing a quantity, a unit value and a total that is not their product is worse than one
     * showing no total at all.
     *
     * <p>A line with no unit value keeps what was sent. There is nothing to multiply by, and the
     * cost the posting will actually use is the running average at the time it posts, which is
     * not a figure a draft can state: it moves with every receipt. {@link #approve} writes the
     * true value onto such a line from the cost it posted at.
     *
     * @param item The line being written, after its variance has been stamped.
     */
    private void stampLineValue(StockAdjustmentLineItem item) {
        BigDecimal value = lineValue(item.getAdjustmentQuantity(), item.getUnitValue());
        if (value != null) {
            item.setTotalAdjustmentValue(value);
        }
    }

    /**
     * A signed quantity valued at a unit price, at the scale the column stores, or null when
     * either figure is missing.
     */
    private BigDecimal lineValue(Double quantity, BigDecimal unitValue) {
        if (quantity == null || unitValue == null) {
            return null;
        }
        return atColumnScale(BigDecimal.valueOf(quantity).multiply(unitValue));
    }

    /**
     * Rounds to the two decimal places {@code total_adjustment_value} is declared with, so the
     * figure held in memory is the figure the database keeps and a document read back straight
     * after being written does not appear to have changed.
     */
    private BigDecimal atColumnScale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sums the header totals from the lines the document actually carries.
     *
     * <p>Both are arithmetic over the lines and nothing else, so neither is a figure the client
     * gets to assert. {@link #approve} restates them from what it posted, which can differ: a line
     * whose movement comes out at nothing is skipped there rather than posted, so a draft and the
     * document it becomes are each the sum of their own lines at the moment they were written.
     *
     * <p>A document with no lines totals zero rather than keeping a number it cannot support.
     *
     * @param stockAdjustment The document, with its lines already attached.
     */
    private void stampHeaderTotals(StockAdjustment stockAdjustment) {
        double variance = 0.0;
        BigDecimal value = BigDecimal.ZERO;
        for (StockAdjustmentLineItem line : stockAdjustment.getLineItems()) {
            if (line.getAdjustmentQuantity() != null) {
                variance += line.getAdjustmentQuantity();
            }
            if (line.getTotalAdjustmentValue() != null) {
                value = value.add(line.getTotalAdjustmentValue());
            }
        }
        stockAdjustment.setTotalVarianceQuantity(variance);
        stockAdjustment.setTotalAdjustmentValue(atColumnScale(value));
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

    /**
     * Reads the display names for every workflow stamp on the adjustments about to be mapped.
     *
     * <p>One call covers a whole page, so the query count does not follow the row count. The
     * mapper cannot do this for itself: a document holds only the user id, and a lookup inside
     * the conversion would cost a round trip per stamp per row with nothing at the call site to
     * show it, which is what {@code MapperDatabaseAccessTest} exists to prevent.
     *
     * @param adjustments The adjustments being converted.
     * @return Their stamp names, with an id that no longer resolves reading as a placeholder.
     */
    private UserNameLookup namesFor(Collection<StockAdjustment> adjustments) {
        return userNameDirectory.namesFor(adjustments.stream()
                .flatMap(adjustment -> Stream.of(adjustment.getSubmittedBy(), adjustment.getApprovedBy(),
                        adjustment.getRejectedBy(), adjustment.getProcessedBy()))
                .toList());
    }

    /**
     * Reads the display names for the workflow stamps on one adjustment.
     *
     * @param adjustment The adjustment being converted.
     * @return Its stamp names.
     */
    private UserNameLookup namesFor(StockAdjustment adjustment) {
        return namesFor(List.of(adjustment));
    }
}
