package org.tornotron.echno_backend.documentReversal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.approval.ApprovalParty;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.history.StatusTransition;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.StatusTransitionSource;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalDto;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalEligibilityDto;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalRequestDto;
import org.tornotron.echno_backend.documentReversal.enums.DocumentReversalStatus;
import org.tornotron.echno_backend.documentReversal.enums.ReversibleDocumentType;
import org.tornotron.echno_backend.documentReversal.mapper.DocumentReversalMapper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteRepository;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.grnItem.GrnItemRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.leave.NotificationDraft;
import org.tornotron.echno_backend.leave.NotificationService;
import org.tornotron.echno_backend.leave.enums.NotificationType;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.PayableRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderService;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransfer.SiteTransferReceiptReconciler;
import org.tornotron.echno_backend.siteTransfer.SiteTransferRepository;
import org.tornotron.echno_backend.siteTransfer.SiteTransferService;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserNameDirectory;
import org.tornotron.echno_backend.user.UserNameLookup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Raises, decides and posts reversals of site transfers, purchase orders and goods received
 * notes. Copies the shape of {@code StockAdjustmentService}: a request is a draft that an
 * approver either posts or refuses with a reason, and both decisions freeze it.
 *
 * <p>Five rules, decided on the product side, are what this class enforces:
 *
 * <ol>
 *   <li>Only the document's creator may ask for its reversal ({@link #request}).
 *   <li>The decision is taken by an approver, who may refuse with a reason
 *       ({@link #approve}, {@link #reject}).
 *   <li>Approval undoes the document's stock movements with correcting ledger entries so every
 *       affected store returns to the balance it held before the document, and the original
 *       stays on the record marked reversed and linked both ways ({@link #postCorrection}).
 *   <li>The store keepers of every affected store are told, so the physical stock is put back.
 *   <li>A document consumed downstream cannot be reversed while the downstream document stands;
 *       the request is refused with a message naming the blocker ({@link #blockerFor}).
 * </ol>
 *
 * <p>The correcting entries are derived from the ledger rather than from the document's lines.
 * Every row a document wrote carries the document's number as its reference, so grouping those
 * rows by balance row and netting the quantity gives exactly what has to be undone, whatever the
 * document's own lines say and whichever legs of a transfer were written. A reversal written
 * from the lines would have to re-derive that, and would be wrong the first time the two
 * disagreed.
 */
@Service
public class DocumentReversalService {

    private static final Logger log = LoggerFactory.getLogger(DocumentReversalService.class);

    /** The correcting entries carry the document number under this prefix. */
    static final String REVERSAL_REFERENCE_PREFIX = "REV-";

    /** Below this a net movement is treated as none, as {@code StockAdjustmentService} does. */
    private static final double NO_MOVEMENT = 1e-9;

    /** The order states a receipt can move; a reversal of a receipt moves them back. */
    private static final Set<PurchaseOrderStatus> RECEIVED_STATES = EnumSet.of(
            PurchaseOrderStatus.PARTIALLY_RECEIVED, PurchaseOrderStatus.FULLY_RECEIVED);

    private final DocumentReversalRepository reversalRepository;
    private final DocumentReversalMapper reversalMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final CurrentEmployeeService currentEmployeeService;
    private final SelfApprovalPolicy selfApprovalPolicy;
    private final UserNameDirectory userNameDirectory;
    private final SiteTransferRepository siteTransferRepository;
    private final SiteTransferItemRepository siteTransferItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    private final GrnItemRepository grnItemRepository;
    private final PayableRepository payableRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryService inventoryService;
    private final StatusTransitionRepository statusTransitionRepository;
    private final StatusTransitionRecorder statusTransitionRecorder;
    private final EmployeeRepository employeeRepository;
    private final NotificationService notificationService;

    public DocumentReversalService(DocumentReversalRepository reversalRepository,
                                   DocumentReversalMapper reversalMapper,
                                   TenantEntityHelper tenantEntityHelper,
                                   UserContextService userContextService,
                                   CurrentEmployeeService currentEmployeeService,
                                   SelfApprovalPolicy selfApprovalPolicy,
                                   UserNameDirectory userNameDirectory,
                                   SiteTransferRepository siteTransferRepository,
                                   SiteTransferItemRepository siteTransferItemRepository,
                                   PurchaseOrderRepository purchaseOrderRepository,
                                   PurchaseOrderItemRepository purchaseOrderItemRepository,
                                   GoodsReceivedNoteRepository goodsReceivedNoteRepository,
                                   GrnItemRepository grnItemRepository,
                                   PayableRepository payableRepository,
                                   InventoryTransactionRepository inventoryTransactionRepository,
                                   InventoryService inventoryService,
                                   StatusTransitionRepository statusTransitionRepository,
                                   StatusTransitionRecorder statusTransitionRecorder,
                                   EmployeeRepository employeeRepository,
                                   NotificationService notificationService) {
        this.reversalRepository = reversalRepository;
        this.reversalMapper = reversalMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
        this.currentEmployeeService = currentEmployeeService;
        this.selfApprovalPolicy = selfApprovalPolicy;
        this.userNameDirectory = userNameDirectory;
        this.siteTransferRepository = siteTransferRepository;
        this.siteTransferItemRepository = siteTransferItemRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.goodsReceivedNoteRepository = goodsReceivedNoteRepository;
        this.grnItemRepository = grnItemRepository;
        this.payableRepository = payableRepository;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.inventoryService = inventoryService;
        this.statusTransitionRepository = statusTransitionRepository;
        this.statusTransitionRecorder = statusTransitionRecorder;
        this.employeeRepository = employeeRepository;
        this.notificationService = notificationService;
    }

    // ---------------------------------------------------------------------------------------
    // Requests
    // ---------------------------------------------------------------------------------------

    /**
     * Raises a reversal request against a document.
     *
     * <p>The requester is the session's user and must be the document's creator: the person
     * who raised a document is the one who knows it was raised wrongly, and the one who has to
     * answer for asking it undone. Nobody else, an administrator included, can ask on their
     * behalf. The blockers are checked here as well as at approval, so a request that could not
     * be approved is refused at once with the reason rather than sitting in a queue.
     *
     * @throws ResourceNotFoundException if the document does not exist in this organization.
     * @throws AccessDeniedException if the caller did not create the document.
     * @throws InvalidRequestException if the document cannot be reversed, or already has a pending request.
     */
    @Transactional
    public DocumentReversalDto request(DocumentReversalRequestDto dto) {
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        ReversibleDocument document = resolve(dto.documentType(), dto.documentId());
        Long requester = requireCurrentUserId();
        if (!document.isCreatedBy(requester)) {
            throw new AccessDeniedException(document.describe() + " can only be reversed at the request "
                    + "of the person who raised it. Ask " + creatorLabel(document) + " to request it.");
        }
        reversalRepository.findFirstByDocumentTypeAndDocumentIdAndOrganization_IdAndStatus(
                        document.type(), document.id(), organization.getId(), DocumentReversalStatus.PENDING)
                .ifPresent(pending -> {
                    throw new InvalidRequestException(document.describe() + " already has reversal request #"
                            + pending.getId() + " awaiting a decision.");
                });
        blockerFor(document).ifPresent(blocker -> {
            throw new InvalidRequestException(blocker);
        });

        DocumentReversal reversal = new DocumentReversal();
        reversal.setOrganization(organization);
        reversal.setDocumentType(document.type());
        reversal.setDocumentId(document.id());
        reversal.setDocumentNumber(document.number());
        reversal.setStatus(DocumentReversalStatus.PENDING);
        reversal.setReason(dto.reason().trim());
        reversal.setRequestedBy(requester);
        reversal.setRequestedAt(LocalDateTime.now());
        DocumentReversal saved = reversalRepository.saveAndFlush(reversal);
        log.info("Reversal #{} requested on {} by user {}", saved.getId(), document.describe(), requester);
        return reversalMapper.toDto(saved, namesFor(saved));
    }

    /**
     * Whether a document can be reversed right now, and whether the caller may ask. Read by the
     * detail screens; answers with the same checks the write path applies rather than a copy.
     */
    @Transactional(readOnly = true)
    public DocumentReversalEligibilityDto eligibility(ReversibleDocumentType type, Long documentId) {
        ReversibleDocument document = resolve(type, documentId);
        Long orgId = TenantContext.getCurrentOrgId();
        Long caller = userContextService.getCurrentUserId();
        Long pendingId = reversalRepository
                .findFirstByDocumentTypeAndDocumentIdAndOrganization_IdAndStatus(
                        type, documentId, orgId, DocumentReversalStatus.PENDING)
                .map(DocumentReversal::getId).orElse(null);
        String blocker = pendingId != null
                ? document.describe() + " already has reversal request #" + pendingId + " awaiting a decision."
                : blockerFor(document).orElse(null);
        return new DocumentReversalEligibilityDto(
                blocker == null, blocker, caller != null && document.isCreatedBy(caller),
                pendingId, document.reversalId());
    }

    // ---------------------------------------------------------------------------------------
    // Decisions
    // ---------------------------------------------------------------------------------------

    /**
     * Approves a pending request: writes the correcting ledger entries, marks the document
     * reversed, links the two, and tells the store keepers of every affected store.
     *
     * <p>Whoever raised the request cannot approve it, unless they hold the break-glass role, on
     * the same reading {@code StockAdjustmentService.approve} takes: an approval is the second
     * pair of eyes on the entries it posts. The blockers are checked again under the row lock,
     * because stock may have been consumed between the request and the decision.
     *
     * @throws InvalidRequestException if the request is not pending, is being self-approved without the break-glass role, or the document is now blocked.
     */
    @Transactional
    public DocumentReversalDto approve(Long id) {
        DocumentReversal reversal = lock(id);
        requirePending(reversal, "approved");
        Long approver = requireCurrentUserId();
        selfApprovalPolicy.checkSelfApproval(
                ApprovalParty.ofUser(reversal.getRequestedBy()),
                ApprovalParty.ofUser(approver),
                "Reversal request #" + id + " on " + reversal.getDocumentNumber());

        ReversibleDocument document = resolve(reversal.getDocumentType(), reversal.getDocumentId());
        blockerFor(document).ifPresent(blocker -> {
            throw new InvalidRequestException(blocker);
        });

        LocalDateTime decidedAt = LocalDateTime.now();
        reversal.setStatus(DocumentReversalStatus.APPROVED);
        reversal.setDecidedBy(approver);
        reversal.setDecidedAt(decidedAt);
        reversal.setDecisionNote("Approved; the document's movements were undone and the stores told.");
        reversal = reversalRepository.saveAndFlush(reversal);

        List<AffectedStore> affected = postCorrection(document, reversal);
        if (!affected.isEmpty()) {
            reversal.setReversalReference(REVERSAL_REFERENCE_PREFIX + document.number());
        }
        markReversed(document, reversal);
        DocumentReversal saved = reversalRepository.saveAndFlush(reversal);

        notifyStores(document, saved, affected);
        notifyRequester(document, saved, NotificationType.DOCUMENT_REVERSAL_APPROVED,
                "Reversal of " + document.describe() + " approved",
                "Your request to reverse " + document.describe() + " was approved. "
                        + (affected.isEmpty() ? "It moved no stock." : "Its stock movements have been undone."));
        log.info("Reversal #{} approved on {} by user {}: {} store row(s) corrected",
                saved.getId(), document.describe(), approver, affected.size());
        return reversalMapper.toDto(saved, namesFor(saved));
    }

    /**
     * Refuses a pending request, keeping the refusal and its reason on the record. Nothing moves.
     * Not subject to the self-approval policy, for the reason a stock adjustment's rejection is
     * not: a rejection posts nothing for a second pair of eyes to check.
     */
    @Transactional
    public DocumentReversalDto reject(Long id, String reason) {
        DocumentReversal reversal = lock(id);
        requirePending(reversal, "rejected");
        String stated = reason == null ? null : reason.trim();
        if (stated == null || stated.isEmpty()) {
            throw new InvalidRequestException("Rejecting reversal request #" + id + " needs a reason. "
                    + "The record of why a reversal was refused is what the rejection is for.");
        }
        reversal.setStatus(DocumentReversalStatus.REJECTED);
        reversal.setDecidedBy(requireCurrentUserId());
        reversal.setDecidedAt(LocalDateTime.now());
        reversal.setDecisionNote(stated);
        DocumentReversal saved = reversalRepository.saveAndFlush(reversal);

        ReversibleDocument document = resolve(saved.getDocumentType(), saved.getDocumentId());
        notifyRequester(document, saved, NotificationType.DOCUMENT_REVERSAL_REJECTED,
                "Reversal of " + document.describe() + " rejected",
                "Your request to reverse " + document.describe() + " was rejected: " + stated);
        return reversalMapper.toDto(saved, namesFor(saved));
    }

    /** Withdraws a pending request. Only the requester may, and it keeps the withdrawn row. */
    @Transactional
    public DocumentReversalDto cancel(Long id) {
        DocumentReversal reversal = lock(id);
        requirePending(reversal, "cancelled");
        Long caller = requireCurrentUserId();
        if (!Objects.equals(caller, reversal.getRequestedBy())) {
            throw new AccessDeniedException("Reversal request #" + id + " can only be withdrawn by the "
                    + "person who raised it. An approver refuses it with POST /document-reversals/{id}/reject.");
        }
        reversal.setStatus(DocumentReversalStatus.CANCELLED);
        reversal.setDecidedBy(caller);
        reversal.setDecidedAt(LocalDateTime.now());
        reversal.setDecisionNote("Withdrawn by the requester.");
        DocumentReversal saved = reversalRepository.saveAndFlush(reversal);
        return reversalMapper.toDto(saved, namesFor(saved));
    }

    // ---------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DocumentReversalDto getById(Long id) {
        DocumentReversal reversal = reversalRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reversal request with ID " + id + " was not found in this organization"));
        return reversalMapper.toDto(reversal, namesFor(reversal));
    }

    @Transactional(readOnly = true)
    public Page<DocumentReversalDto> getAll(int pageNo, int pageSize, DocumentReversalStatus status) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "requestedAt", "id"));
        Long orgId = TenantContext.getCurrentOrgId();
        Page<DocumentReversal> page = status == null
                ? reversalRepository.findByOrganization_Id(orgId, pageable)
                : reversalRepository.findByOrganization_IdAndStatus(orgId, status, pageable);
        UserNameLookup names = namesFor(page.getContent());
        return page.map(reversal -> reversalMapper.toDto(reversal, names));
    }

    @Transactional(readOnly = true)
    public List<DocumentReversalDto> getByDocument(ReversibleDocumentType type, Long documentId) {
        List<DocumentReversal> rows = reversalRepository
                .findByDocumentTypeAndDocumentIdAndOrganization_IdOrderByRequestedAtDesc(
                        type, documentId, TenantContext.getCurrentOrgId());
        UserNameLookup names = namesFor(rows);
        return rows.stream().map(reversal -> reversalMapper.toDto(reversal, names)).toList();
    }

    // ---------------------------------------------------------------------------------------
    // The documents
    // ---------------------------------------------------------------------------------------

    /**
     * One document as the reversal sees it: enough to name it, to say who raised it, to say
     * whether it is already undone, and to reach its rows when it has to be undone.
     */
    record ReversibleDocument(ReversibleDocumentType type, Long id, String number, String kind,
                              Long creatorUserId, Long reversalId, Object entity) {

        boolean isCreatedBy(Long userId) {
            return creatorUserId != null && creatorUserId.equals(userId);
        }

        String describe() {
            return kind + " " + number;
        }
    }

    private ReversibleDocument resolve(ReversibleDocumentType type, Long documentId) {
        Long orgId = TenantContext.getCurrentOrgId();
        return switch (type) {
            case SITE_TRANSFER -> {
                SiteTransfer transfer = siteTransferRepository.findByIdAndOrganization_Id(documentId, orgId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Site transfer with ID " + documentId + " was not found in this organization"));
                Long creator = creationActor(SiteTransferService.HISTORY_ENTITY_TYPE, transfer.getId(), orgId)
                        .orElseGet(() -> userOf(transfer.getSendingPerson()));
                yield new ReversibleDocument(type, transfer.getId(), transfer.getTransferNumber(),
                        "Site transfer", creator, transfer.getReversalId(), transfer);
            }
            case PURCHASE_ORDER -> {
                PurchaseOrder order = purchaseOrderRepository.findByIdAndOrganization_Id(documentId, orgId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Purchase order with ID " + documentId + " was not found in this organization"));
                Long creator = creationActor(PurchaseOrderService.HISTORY_ENTITY_TYPE, order.getId(), orgId)
                        .orElseGet(() -> userOf(order.getCreatedBy()));
                yield new ReversibleDocument(type, order.getId(), order.getPoNumber(),
                        "Purchase order", creator, order.getReversalId(), order);
            }
            case GOODS_RECEIVED_NOTE -> {
                GoodsReceivedNote grn = goodsReceivedNoteRepository.findByIdAndOrganization_Id(documentId, orgId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Goods received note with ID " + documentId + " was not found in this organization"));
                yield new ReversibleDocument(type, grn.getId(), grn.getGrnNumber(),
                        "Goods received note", userOf(grn.getReceivedBy()), grn.getReversalId(), grn);
            }
        };
    }

    /**
     * The user who recorded the document's creation, from its status trail. The trail is the
     * one place that names the session rather than a payload field, which is why it is read
     * first; the payload's person is the fallback for documents written before the trail was.
     */
    private Optional<Long> creationActor(String entityType, Long entityId, Long orgId) {
        return statusTransitionRepository
                .findFirstByEntityTypeAndEntityIdAndOrganization_IdAndSourceOrderByOccurredAtAscIdAsc(
                        entityType, entityId, orgId, StatusTransitionSource.CREATION)
                .map(StatusTransition::getChangedBy);
    }

    private static Long userOf(Employee employee) {
        return employee != null && employee.getUser() != null ? employee.getUser().getId() : null;
    }

    private String creatorLabel(ReversibleDocument document) {
        if (document.creatorUserId() == null) {
            return "whoever raised it";
        }
        String name = userNameDirectory.namesFor(List.of(document.creatorUserId())).nameOf(document.creatorUserId());
        return name != null ? name : "whoever raised it";
    }

    // ---------------------------------------------------------------------------------------
    // Blockers
    // ---------------------------------------------------------------------------------------

    /**
     * What stands in the way of reversing the document, or nothing.
     *
     * <p>A downstream document is one that only makes sense because this one stands: a receipt
     * against an order, an arrival against a transfer, a payable against a receipt, an issue of
     * the stock a receipt brought in. Reversing underneath it would leave it describing
     * something that no longer happened, so the request is refused and the message names what
     * has to be dealt with first.
     */
    Optional<String> blockerFor(ReversibleDocument document) {
        if (document.reversalId() != null) {
            return Optional.of(document.describe() + " has already been reversed by reversal #"
                    + document.reversalId() + ".");
        }
        return switch (document.type()) {
            case SITE_TRANSFER -> transferBlocker((SiteTransfer) document.entity(), document);
            case PURCHASE_ORDER -> orderBlocker((PurchaseOrder) document.entity(), document);
            case GOODS_RECEIVED_NOTE -> receiptBlocker((GoodsReceivedNote) document.entity(), document);
        };
    }

    private Optional<String> transferBlocker(SiteTransfer transfer, ReversibleDocument document) {
        if (transfer.getStatus() == SiteTransferStatus.CANCELLED) {
            return Optional.of(document.describe() + " was cancelled in transit and its stock is already "
                    + "back at the sending site; there is nothing to reverse.");
        }
        if (transfer.getStatus() == SiteTransferStatus.REVERSED) {
            return Optional.of(document.describe() + " has already been reversed.");
        }
        if (SiteTransferReceiptReconciler.crossesProjectBoundary(transfer)) {
            List<SiteTransferItem> lines = siteTransferItemRepository.findBySiteTransferId(transfer.getId());
            int received = lines.stream()
                    .mapToInt(line -> line.getReceivedQuantity() != null ? line.getReceivedQuantity() : 0)
                    .sum();
            if (received > 0) {
                String receivingProject = transfer.getReceivingProject() != null
                        ? transfer.getReceivingProject().getProjectName() : "the receiving site";
                return Optional.of(document.describe() + " has had " + received + " unit(s) received at "
                        + receivingProject + ". A receipt at the destination stands as a downstream "
                        + "document, so the transfer cannot be reversed while it does; correct what "
                        + "arrived with a stock adjustment naming this transfer.");
            }
        }
        return consumedStockBlocker(document);
    }

    private Optional<String> orderBlocker(PurchaseOrder order, ReversibleDocument document) {
        if (order.getStatus() == PurchaseOrderStatus.CANCELLED) {
            return Optional.of(document.describe() + " is cancelled; there is nothing to reverse.");
        }
        if (order.getStatus() == PurchaseOrderStatus.REVERSED) {
            return Optional.of(document.describe() + " has already been reversed.");
        }
        List<String> standing = goodsReceivedNoteRepository
                .findByPurchaseOrder_IdAndOrganization_Id(order.getId(), TenantContext.getCurrentOrgId())
                .stream()
                .filter(grn -> grn.getReversalId() == null)
                .map(GoodsReceivedNote::getGrnNumber)
                .toList();
        if (!standing.isEmpty()) {
            return Optional.of(document.describe() + " has goods received note"
                    + (standing.size() == 1 ? " " : "s ") + String.join(", ", standing)
                    + " receipted against it. A receipt stands as a downstream document, so the order "
                    + "cannot be reversed while it does; reverse the receipt first.");
        }
        return Optional.empty();
    }

    private Optional<String> receiptBlocker(GoodsReceivedNote grn, ReversibleDocument document) {
        if (payableRepository.existsByGoodsReceivedNote_IdAndOrganization_Id(grn.getId(), TenantContext.getCurrentOrgId())) {
            return Optional.of(document.describe() + " has a payable raised against it. The payable stands "
                    + "as a downstream document, so the receipt cannot be reversed while it does.");
        }
        return consumedStockBlocker(document);
    }

    /**
     * Whether every store the document credited still holds what would have to come back.
     * Stock issued since the document is the downstream that a ledger cannot name a document
     * for, so the balance is the check: a row that would go negative names what was consumed.
     */
    private Optional<String> consumedStockBlocker(ReversibleDocument document) {
        for (LedgerGroup group : ledgerGroups(document)) {
            double correction = -group.net();
            if (correction >= -NO_MOVEMENT) {
                continue;
            }
            double balance = balanceAt(group);
            double closing = balance + correction;
            if (closing < -NO_MOVEMENT) {
                return Optional.of(String.format(
                        "Reversing %s would take %s of %s back out of %s, which holds only %s: %s has been "
                                + "consumed since. Stock issued after a document stands downstream of it, "
                                + "so the document cannot be reversed while the balance is short; correct "
                                + "the balance with a stock adjustment instead.",
                        document.describe(), quantity(-correction), group.material().getMaterialName(),
                        storeName(group.project(), group.location()), quantity(balance),
                        quantity(-closing)));
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------
    // Posting the correction
    // ---------------------------------------------------------------------------------------

    /** One balance row the document moved, with everything the correction needs. */
    private record LedgerGroup(Material material, Project project, StorageLocation location,
                               double net, double inboundQuantity, BigDecimal inboundValue,
                               double outboundQuantity, BigDecimal outboundValue) {

        LedgerGroup plus(InventoryTransaction row) {
            double qty = row.getQuantityChanged() != null ? row.getQuantityChanged() : 0.0;
            double in = inboundQuantity, out = outboundQuantity;
            BigDecimal inValue = inboundValue, outValue = outboundValue;
            if (row.getUnitCost() != null) {
                if (qty > 0) {
                    in += qty;
                    inValue = inValue.add(row.getUnitCost().multiply(BigDecimal.valueOf(qty)));
                } else if (qty < 0) {
                    out += -qty;
                    outValue = outValue.add(row.getUnitCost().multiply(BigDecimal.valueOf(-qty)));
                }
            }
            return new LedgerGroup(material, project, location, net + qty, in, inValue, out, outValue);
        }

        /**
         * The cost stock comes back at when the correction is inbound: what the outbound rows
         * carried it out at, weighted, so the store's value returns to where it was.
         */
        Optional<BigDecimal> outboundUnitCost() {
            if (outboundQuantity <= 0) {
                return Optional.empty();
            }
            return Optional.of(outboundValue.divide(BigDecimal.valueOf(outboundQuantity), 2, RoundingMode.HALF_UP));
        }
    }

    /** A store the correction touched, for the notification. */
    record AffectedStore(Project project, StorageLocation location, Material material, double quantityPutBack) {
    }

    private record RowKey(Long materialId, Long projectId, Long locationId) {
    }

    private List<LedgerGroup> ledgerGroups(ReversibleDocument document) {
        List<InventoryTransaction> rows = inventoryTransactionRepository
                .findByReferenceNumberAndOrganization_IdOrderByIdAsc(document.number(), TenantContext.getCurrentOrgId());
        Map<RowKey, LedgerGroup> groups = new LinkedHashMap<>();
        for (InventoryTransaction row : rows) {
            if (row.getTransactionType() == InventoryTransactionType.REVERSAL || row.getMaterial() == null
                    || row.getProject() == null) {
                continue;
            }
            RowKey key = new RowKey(row.getMaterial().getId(), row.getProject().getId(),
                    row.getStorageLocation() != null ? row.getStorageLocation().getId() : null);
            LedgerGroup group = groups.getOrDefault(key, new LedgerGroup(row.getMaterial(), row.getProject(),
                    row.getStorageLocation(), 0.0, 0.0, BigDecimal.ZERO, 0.0, BigDecimal.ZERO));
            groups.put(key, group.plus(row));
        }
        return new ArrayList<>(groups.values());
    }

    private double balanceAt(LedgerGroup group) {
        Optional<Double> balance = group.location() != null
                ? inventoryService.findStockAtLocation(group.material().getId(), group.project().getId(),
                        group.location().getId())
                : inventoryService.findUnlocatedStock(group.material().getId(), group.project().getId());
        return balance.orElse(0.0);
    }

    /**
     * Writes one {@code REVERSAL} entry per balance row the document moved, for the opposite of
     * the net movement, and moves the balance. Runs in the approval's transaction, as a stock
     * adjustment's posting does: an approval that recorded no movement is the failure this
     * exists to remove.
     *
     * @return The rows where stock came back, which are the stores that have to put it back physically.
     */
    private List<AffectedStore> postCorrection(ReversibleDocument document, DocumentReversal reversal) {
        List<AffectedStore> affected = new ArrayList<>();
        Organization organization = reversal.getOrganization();
        Employee actor = currentEmployeeService.currentEmployee().orElse(null);
        LocalDateTime postedAt = LocalDateTime.now();
        String reference = REVERSAL_REFERENCE_PREFIX + document.number();

        for (LedgerGroup group : ledgerGroups(document)) {
            double correction = -group.net();
            if (Math.abs(correction) < NO_MOVEMENT) {
                continue;
            }
            double opening = balanceAt(group);
            double closing = opening + correction;
            if (closing < -NO_MOVEMENT) {
                // The eligibility check ran a moment ago; this is the same check under the lock.
                throw new InvalidRequestException(consumedStockBlocker(document).orElse(
                        "Reversing " + document.describe() + " would drive a balance below zero."));
            }
            BigDecimal unitCost = correction > 0
                    ? group.outboundUnitCost().orElseGet(() -> inventoryService.getAverageCost(
                            group.material().getId(), group.project().getId(),
                            group.location() != null ? group.location().getId() : null))
                    : inventoryService.getAverageCost(group.material().getId(), group.project().getId(),
                            group.location() != null ? group.location().getId() : null);

            InventoryTransaction entry = new InventoryTransaction();
            entry.setTransactionDate(postedAt);
            entry.setMaterial(group.material());
            entry.setOpeningStock(opening);
            entry.setQuantityChanged(correction);
            entry.setClosingStock(closing);
            entry.setTransactionType(InventoryTransactionType.REVERSAL);
            entry.setReferenceNumber(reference);
            entry.setRemarks("Reversal of " + document.describe() + " (request #" + reversal.getId() + ") - "
                    + reversal.getReason());
            entry.setCreatedBy(actor);
            entry.setProject(group.project());
            entry.setStorageLocation(group.location());
            entry.setOrganization(organization);
            entry.setUnitCost(unitCost);
            inventoryTransactionRepository.save(entry);

            inventoryService.updateCurrentStock(group.material(), group.project(), group.location(),
                    organization, correction, correction > 0 ? unitCost : null);

            if (correction > 0) {
                affected.add(new AffectedStore(group.project(), group.location(), group.material(), correction));
            }
            log.debug("Reversal #{}: {} {} at {} corrected by {}", reversal.getId(), document.describe(),
                    group.material().getMaterialName(), storeName(group.project(), group.location()), correction);
        }
        return affected;
    }

    /** Stamps the document as reversed and links it to the request; moves what has a status. */
    private void markReversed(ReversibleDocument document, DocumentReversal reversal) {
        Organization organization = reversal.getOrganization();
        String note = "Reversed under request #" + reversal.getId() + ": " + reversal.getReason();
        switch (document.type()) {
            case SITE_TRANSFER -> {
                SiteTransfer transfer = (SiteTransfer) document.entity();
                SiteTransferStatus previous = transfer.getStatus();
                transfer.setStatus(SiteTransferStatus.REVERSED);
                transfer.setReversalId(reversal.getId());
                siteTransferRepository.save(transfer);
                statusTransitionRecorder.recordChange(SiteTransferService.HISTORY_ENTITY_TYPE, transfer.getId(),
                        organization, previous != null ? previous.name() : null,
                        SiteTransferStatus.REVERSED.name(), userContextService.getCurrentUser(), note);
            }
            case PURCHASE_ORDER -> {
                PurchaseOrder order = (PurchaseOrder) document.entity();
                PurchaseOrderStatus previous = order.getStatus();
                order.setStatus(PurchaseOrderStatus.REVERSED);
                order.setReversalId(reversal.getId());
                purchaseOrderRepository.save(order);
                statusTransitionRecorder.recordChange(PurchaseOrderService.HISTORY_ENTITY_TYPE, order.getId(),
                        organization, previous != null ? previous.name() : null,
                        PurchaseOrderStatus.REVERSED.name(), userContextService.getCurrentUser(), note);
            }
            case GOODS_RECEIVED_NOTE -> {
                GoodsReceivedNote grn = (GoodsReceivedNote) document.entity();
                grn.setReversalId(reversal.getId());
                goodsReceivedNoteRepository.save(grn);
                takeReceiptOffTheOrder(grn, reversal);
            }
        }
    }

    /**
     * Takes a reversed receipt's quantities back off the order it was receipted against and
     * re-derives the order's status, the mirror of what
     * {@code PurchaseOrderReceiptReconciler.applyReceipt} did when the receipt was recorded.
     */
    private void takeReceiptOffTheOrder(GoodsReceivedNote grn, DocumentReversal reversal) {
        PurchaseOrder order = grn.getPurchaseOrder();
        if (order == null) {
            return;
        }
        Long orgId = TenantContext.getCurrentOrgId();
        List<PurchaseOrderItem> orderLines = purchaseOrderItemRepository
                .lockByPurchaseOrderIdAndOrganizationId(order.getId(), orgId);
        List<GrnItem> receiptLines = grnItemRepository.findByGoodsReceivedNoteId(grn.getId());
        for (GrnItem receiptLine : receiptLines) {
            if (receiptLine.getMaterial() == null || receiptLine.getReceivedQuantity() == null) {
                continue;
            }
            int remaining = receiptLine.getReceivedQuantity();
            // Walk the order's lines for this material from the last one back, the reverse of
            // the order the receipt was allocated in, taking no line below zero.
            for (int i = orderLines.size() - 1; i >= 0 && remaining > 0; i--) {
                PurchaseOrderItem line = orderLines.get(i);
                if (line.getMaterial() == null
                        || !Objects.equals(line.getMaterial().getId(), receiptLine.getMaterial().getId())) {
                    continue;
                }
                int held = line.getReceivedQuantity() != null ? line.getReceivedQuantity() : 0;
                int take = Math.min(held, remaining);
                line.setReceivedQuantity(held - take);
                remaining -= take;
            }
        }
        purchaseOrderItemRepository.saveAll(orderLines);

        PurchaseOrderStatus current = order.getStatus();
        if (current == null || !RECEIVED_STATES.contains(current)) {
            return;
        }
        boolean anyReceived = orderLines.stream()
                .anyMatch(line -> line.getReceivedQuantity() != null && line.getReceivedQuantity() > 0);
        PurchaseOrderStatus target = anyReceived
                ? PurchaseOrderStatus.PARTIALLY_RECEIVED
                : statusBeforeReceipts(order, orgId);
        if (target == current) {
            return;
        }
        order.setStatus(target);
        purchaseOrderRepository.save(order);
        statusTransitionRecorder.recordSystemChange(PurchaseOrderService.HISTORY_ENTITY_TYPE, order.getId(),
                order.getOrganization(), current.name(), target.name(),
                "Derived from the quantities received against this order after goods receipt "
                        + grn.getGrnNumber() + " was reversed under request #" + reversal.getId() + ".");
    }

    /**
     * The status the order held before its first receipt moved it, read from its trail; the
     * approved state when the trail does not say.
     */
    private PurchaseOrderStatus statusBeforeReceipts(PurchaseOrder order, Long orgId) {
        List<StatusTransition> trail = statusTransitionRepository
                .findByEntityTypeAndEntityIdAndOrganization_IdOrderByOccurredAtDescIdDesc(
                        PurchaseOrderService.HISTORY_ENTITY_TYPE, order.getId(), orgId, PageRequest.of(0, 100))
                .getContent();
        PurchaseOrderStatus before = null;
        for (StatusTransition transition : trail) {
            if (transition.getFromStatus() == null) {
                continue;
            }
            boolean intoReceived;
            try {
                intoReceived = RECEIVED_STATES.contains(PurchaseOrderStatus.valueOf(transition.getToStatus()));
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            if (!intoReceived) {
                continue;
            }
            try {
                PurchaseOrderStatus from = PurchaseOrderStatus.valueOf(transition.getFromStatus());
                if (!RECEIVED_STATES.contains(from)) {
                    before = from;
                }
            } catch (IllegalArgumentException unknown) {
                // A status the enum no longer has; keep looking.
            }
        }
        return before != null ? before : PurchaseOrderStatus.APPROVED;
    }

    // ---------------------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------------------

    /**
     * Tells the store keepers of every store that has stock to put back. A storage location
     * carries no responsible person, so the recipients are the store keepers assigned to the
     * store's project, and the project managers where it has none: the rule the low-stock
     * sweep settled on for the same question.
     */
    private void notifyStores(ReversibleDocument document, DocumentReversal reversal, List<AffectedStore> affected) {
        Map<String, List<AffectedStore>> byStore = new LinkedHashMap<>();
        for (AffectedStore store : affected) {
            byStore.computeIfAbsent(storeName(store.project(), store.location()), key -> new ArrayList<>()).add(store);
        }
        Long orgId = reversal.getOrganization().getId();
        for (Map.Entry<String, List<AffectedStore>> entry : byStore.entrySet()) {
            Project project = entry.getValue().get(0).project();
            List<Employee> recipients = employeeRepository.findByProjectAndOrgRole(orgId, project.getId(), OrgRole.STORE_KEEPER);
            if (recipients.isEmpty()) {
                recipients = employeeRepository.findByProjectAndOrgRole(orgId, project.getId(), OrgRole.PROJECT_MANAGER);
            }
            if (recipients.isEmpty()) {
                log.info("Reversal #{}: nobody to tell at {} (no store keeper or project manager on project {})",
                        reversal.getId(), entry.getKey(), project.getId());
                continue;
            }
            StringBuilder lines = new StringBuilder();
            for (AffectedStore store : entry.getValue()) {
                if (lines.length() > 0) {
                    lines.append("; ");
                }
                lines.append(quantity(store.quantityPutBack())).append(" ").append(store.material().getMaterialName());
            }
            NotificationDraft draft = new NotificationDraft(
                    NotificationType.DOCUMENT_REVERSAL_APPROVED,
                    document.describe() + " reversed: stock returns to " + entry.getKey(),
                    "The reversal of " + document.describe() + " was approved. Put the following back on the shelf at "
                            + entry.getKey() + ": " + lines + ". Reason given: " + reversal.getReason(),
                    document.type().name(), document.id(), actionUrl(document));
            notificationService.deliverToAll(recipients, draft);
        }
    }

    private void notifyRequester(ReversibleDocument document, DocumentReversal reversal, NotificationType type,
                                 String title, String message) {
        employeeRepository.findByUserIdAndOrganizationId(reversal.getRequestedBy(), reversal.getOrganization().getId())
                .ifPresent(requester -> notificationService.deliver(requester, new NotificationDraft(
                        type, title, message, document.type().name(), document.id(), actionUrl(document))));
    }

    private static String actionUrl(ReversibleDocument document) {
        return switch (document.type()) {
            case SITE_TRANSFER -> "/users/dashboard/resources/transfers/" + document.id();
            case PURCHASE_ORDER -> "/users/dashboard/resources/purchase-orders/" + document.id();
            case GOODS_RECEIVED_NOTE -> "/users/dashboard/resources/goods-receipts/" + document.id();
        };
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private Long requireCurrentUserId() {
        Long userId = userContextService.getCurrentUserId();
        if (userId == null) {
            throw new AccessDeniedException("This session resolves to no user of the organization, so there is "
                    + "nobody to record as having asked for or decided the reversal.");
        }
        return userId;
    }

    private DocumentReversal lock(Long id) {
        return reversalRepository.lockByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reversal request with ID " + id + " was not found in this organization"));
    }

    private static void requirePending(DocumentReversal reversal, String action) {
        if (reversal.getStatus() != DocumentReversalStatus.PENDING) {
            throw new InvalidRequestException("Reversal request #" + reversal.getId() + " is "
                    + reversal.getStatus() + " and cannot be " + action + ". Only a pending request can be decided.");
        }
    }

    private static String storeName(Project project, StorageLocation location) {
        String projectName = project != null && project.getProjectName() != null
                ? project.getProjectName() : "project " + (project != null ? project.getId() : "?");
        if (location != null) {
            return (location.getLocationName() != null ? location.getLocationName() : "store " + location.getId())
                    + " (" + projectName + ")";
        }
        return projectName + " (no storage location)";
    }

    private static String quantity(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format("%.2f", value);
    }

    private UserNameLookup namesFor(Collection<DocumentReversal> reversals) {
        return userNameDirectory.namesFor(reversals.stream()
                .flatMap(reversal -> Stream.of(reversal.getRequestedBy(), reversal.getDecidedBy()))
                .toList());
    }

    private UserNameLookup namesFor(DocumentReversal reversal) {
        return namesFor(List.of(reversal));
    }
}
