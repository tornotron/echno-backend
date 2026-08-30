package org.tornotron.echno_backend.finance.construction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.approval.ApprovalParty;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoiceLine;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineRequest;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapper;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceRepository;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceSpecifications;
import org.tornotron.echno_backend.finance.invoice.dtos.CreateInvoiceRequest;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.ReverseJournalRequest;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsService;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CRUD, list, and the approval + ledger-posting lifecycle for construction invoices.
 *
 * <p>Money totals are computed arithmetically from the line items on create/update.
 * The lifecycle methods add the approval gate and the ledger hooks that mirror the AR
 * invoice module: {@link #submit} moves a draft into approval, {@link #approve} posts
 * the journal entry (posting trigger is approval, not draft), and {@link #cancel}
 * reverses that entry. Posting is invoice-level to the default accounts configured on
 * {@link ConstructionPostingProperties}; per-line cost-category posting is the later
 * budgeting phase.
 *
 * <p>Sales and service invoices bill through accounts receivable. Where the invoice's project
 * names a client, approval materializes a real AR {@link org.tornotron.echno_backend.finance.invoice.domain.Invoice}
 * against that customer and takes its journal entry as its own, so the amount appears in AR
 * aging and can be settled by an ordinary receipt. Where the project has no client, the
 * receivable entry is posted directly and no AR document exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConstructionInvoiceService {

    private static final String DOC_TYPE = "CINV";
    private static final String SOURCE_TYPE = "CONSTRUCTION_INVOICE";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ConstructionInvoiceRepository invoiceRepo;
    private final EntryNumberGenerator numberGen;
    private final ConstructionInvoiceMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final JournalEntryRepository journalRepo;
    private final JournalPostingService postingService;
    private final PostingAccountResolver postingAccountResolver;
    private final FinanceSettingsService financeSettingsService;
    private final ProjectRepository projectRepository;
    private final UserContextService userContextService;
    private final CostCategoryRepository costCategoryRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceService invoiceService;
    private final SelfApprovalPolicy selfApprovalPolicy;

    @Transactional(readOnly = true)
    public ConstructionInvoiceDto findById(UUID id) {
        return mapper.toDto(invoiceRepo.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction invoice with ID " + id + " was not found")));
    }

    @Transactional(readOnly = true)
    public Page<ConstructionInvoiceDto> findAll(Long projectId,
                                                Long vendorId,
                                                ConstructionInvoiceStatus status,
                                                ConstructionInvoiceType type,
                                                Pageable pageable) {
        return invoiceRepo.findAll(
                        ConstructionInvoiceSpecifications.withFilters(projectId, vendorId, status, type),
                        pageable)
                .map(mapper::toDto);
    }

    @Transactional
    public ConstructionInvoiceDto create(CreateConstructionInvoiceRequest req) {
        if (req.dueDate().isBefore(req.issueDate())) {
            throw new InvalidRequestException("Due date cannot be before issue date");
        }

        ConstructionInvoice inv = new ConstructionInvoice();
        inv.setInvoiceNumber(numberGen.next(DOC_TYPE));
        inv.setType(req.type());
        inv.setStatus(ConstructionInvoiceStatus.DRAFT);
        inv.setPaymentStatus(ConstructionPaymentStatus.UNPAID);
        inv.setProjectId(req.projectId());
        inv.setVendorId(req.vendorId());
        inv.setPurchaseOrderId(req.purchaseOrderId());
        inv.setGoodsReceiptId(req.goodsReceiptId());
        inv.setIssueDate(req.issueDate());
        inv.setDueDate(req.dueDate());
        inv.setPaymentTerms(req.paymentTerms());
        inv.setPaymentMethod(req.paymentMethod());
        inv.setGstNumber(req.gstNumber());
        inv.setTaxType(req.taxType());
        inv.setNotes(req.notes());
        inv.setTermsAndConditions(req.termsAndConditions());
        inv.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        applyLinesAndTotals(inv, req.lines());

        ConstructionInvoice saved = invoiceRepo.save(inv);
        log.info("Created construction invoice {}", saved.getInvoiceNumber());
        return mapper.toDto(saved);
    }

    @Transactional
    public ConstructionInvoiceDto update(UUID id, UpdateConstructionInvoiceRequest req) {
        if (req.dueDate().isBefore(req.issueDate())) {
            throw new InvalidRequestException("Due date cannot be before issue date");
        }

        ConstructionInvoice inv = invoiceRepo.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction invoice with ID " + id + " was not found"));

        if (inv.getStatus() == ConstructionInvoiceStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Construction invoice " + inv.getInvoiceNumber() + " is cancelled and cannot be updated");
        }

        // Approval is a posting event, so it may only happen through approve(), which
        // writes the journal entry. A plain edit must never move an invoice into APPROVED
        // (that would leave an approved invoice with no ledger entry) or out of it.
        if (req.status() == ConstructionInvoiceStatus.APPROVED
                && inv.getStatus() != ConstructionInvoiceStatus.APPROVED) {
            throw new InvalidRequestException(
                    "Construction invoice " + inv.getInvoiceNumber()
                            + " cannot be approved through an update; use the approve action");
        }

        inv.setType(req.type());
        inv.setStatus(req.status());
        inv.setPaymentStatus(req.paymentStatus());
        inv.setProjectId(req.projectId());
        inv.setVendorId(req.vendorId());
        inv.setPurchaseOrderId(req.purchaseOrderId());
        inv.setGoodsReceiptId(req.goodsReceiptId());
        inv.setIssueDate(req.issueDate());
        inv.setDueDate(req.dueDate());
        inv.setPaymentDate(req.paymentDate());
        inv.setPaymentTerms(req.paymentTerms());
        inv.setPaymentMethod(req.paymentMethod());
        inv.setGstNumber(req.gstNumber());
        inv.setTaxType(req.taxType());
        inv.setNotes(req.notes());
        inv.setTermsAndConditions(req.termsAndConditions());

        inv.getLines().clear();
        applyLinesAndTotals(inv, req.lines());

        ConstructionInvoice saved = invoiceRepo.saveAndFlush(inv);
        log.info("Updated construction invoice {}", saved.getInvoiceNumber());
        return mapper.toDto(saved);
    }

    /**
     * Submit a draft for approval: DRAFT -> PENDING. Records who submitted it and when.
     *
     * <p>When an auto-approval threshold applies (per-project override where set, else the
     * organization-level finance setting) and the invoice total is strictly below it, the invoice
     * is approved and posted straight away through the same path as {@link #approve}, rather than
     * left waiting in PENDING. A null effective threshold means every invoice needs manual approval,
     * which is the original behaviour.
     *
     * <p>The threshold path is the one approval that is allowed to be the submitter's own: the
     * threshold is a standing decision by the tenant that invoices under it are not worth a second
     * pair of eyes. Above it, and where no threshold is set, the invoice goes to
     * {@link #approve}, which requires a different person.
     */
    @Transactional
    public ConstructionInvoiceDto submit(UUID id) {
        ConstructionInvoice inv = requireInvoice(id);
        if (inv.getStatus() != ConstructionInvoiceStatus.DRAFT) {
            throw new InvalidRequestException(
                    "Only DRAFT construction invoices can be submitted; invoice "
                            + inv.getInvoiceNumber() + " is currently " + inv.getStatus());
        }
        inv.setSubmittedBy(userContextService.getCurrentUserId());
        inv.setSubmittedAt(Instant.now());

        BigDecimal threshold = effectiveApprovalThreshold(inv);
        if (threshold != null && inv.getTotalAmount().compareTo(threshold) < 0) {
            postAndApprove(inv);
            log.info("Auto-approved construction invoice {} under threshold {} on submit",
                    inv.getInvoiceNumber(), threshold);
            return mapper.toDto(inv);
        }

        inv.setStatus(ConstructionInvoiceStatus.PENDING);
        log.info("Submitted construction invoice {} for approval", inv.getInvoiceNumber());
        return mapper.toDto(inv);
    }

    /**
     * Approve a pending invoice: PENDING -> APPROVED, and post the journal entry.
     *
     * <p>Whoever submitted the invoice cannot approve it, on the same rule as every other
     * approval that posts an entry: see {@link SelfApprovalPolicy}. The auto-approval inside
     * {@link #submit} is deliberately not subject to it, because a threshold set on the
     * organization or the project is the tenant's own recorded decision that invoices below that
     * figure do not need a second person, and passing the submitter's own invoice through it is
     * exactly what it was configured for.
     *
     * <p>Posting is invoice-level to the accounts resolved for each posting role (a per-org mapping
     * where set, else the configured default account):
     * <ul>
     *   <li>PURCHASE / EXPENSE: DR default expense (net of discount) + DR GST input
     *       (tax, if any); CR Accounts Payable (gross total).</li>
     *   <li>SALES / SERVICE: an AR invoice is raised to the project's client and its own entry
     *       (DR Accounts Receivable; CR revenue + CR GST output) becomes this invoice's entry.
     *       With no client on the project, the same entry is posted directly instead.</li>
     * </ul>
     * The posted journal-entry id is stored on the invoice for drill-back and reversal.
     */
    @Transactional
    public ConstructionInvoiceDto approve(UUID id) {
        ConstructionInvoice inv = requireInvoice(id);
        if (inv.getStatus() != ConstructionInvoiceStatus.PENDING) {
            throw new InvalidRequestException(
                    "Only PENDING construction invoices can be approved; invoice "
                            + inv.getInvoiceNumber() + " is currently " + inv.getStatus());
        }
        selfApprovalPolicy.checkSelfApproval(
                ApprovalParty.ofUser(inv.getSubmittedBy()),
                ApprovalParty.ofUser(userContextService.getCurrentUserId()),
                "Construction invoice " + inv.getInvoiceNumber());
        postAndApprove(inv);
        return mapper.toDto(inv);
    }

    /**
     * The effective auto-approval threshold for an invoice: the invoice's project override where
     * set, otherwise the organization-level finance setting. Null means manual approval is always
     * required.
     */
    private BigDecimal effectiveApprovalThreshold(ConstructionInvoice inv) {
        if (inv.getProjectId() != null) {
            BigDecimal projectThreshold = projectRepository
                    .findByIdAndOrganization_Id(inv.getProjectId(), TenantContext.getCurrentOrgId())
                    .map(Project::getApprovalThreshold)
                    .orElse(null);
            if (projectThreshold != null) {
                return projectThreshold;
            }
        }
        return financeSettingsService.getApprovalThreshold();
    }

    /**
     * Posts the ledger journal entry for the invoice and moves it to APPROVED, recording the
     * approving user. Shared by {@link #approve} and the auto-approval path in {@link #submit}, so
     * both raise an identical journal entry.
     *
     * <p>A sales or service invoice whose project has a client takes the AR route: the entry comes
     * from the AR invoice raised for it. Everything else posts its own entry from the invoice
     * totals.
     */
    private void postAndApprove(ConstructionInvoice inv) {
        if (isReceivableType(inv.getType())) {
            Customer client = resolveProjectClient(inv);
            if (client != null && !inv.getLines().isEmpty()) {
                materializeArInvoice(inv, client);
                markApproved(inv);
                log.info("Approved construction invoice {} through AR invoice {} for customer {}",
                        inv.getInvoiceNumber(), inv.getArInvoiceId(), client.getCode());
                return;
            }
        }

        BigDecimal net = MoneyUtils.normalize(inv.getSubtotal().subtract(inv.getDiscountAmount()));
        BigDecimal tax = MoneyUtils.normalize(inv.getTaxAmount());
        BigDecimal gross = MoneyUtils.normalize(inv.getTotalAmount());

        List<PostJournalRequest.LineRequest> jeLines = new ArrayList<>();
        switch (inv.getType()) {
            case PURCHASE, EXPENSE -> {
                // The payable side has no document to materialize: purchase and expense
                // invoices always post their own entry.
                Account expense = postingAccountResolver.resolve(PostingRole.DEFAULT_EXPENSE);
                Account payable = postingAccountResolver.resolve(PostingRole.ACCOUNTS_PAYABLE);
                jeLines.add(new PostJournalRequest.LineRequest(expense.getId(), net, BigDecimal.ZERO,
                        "Expense - " + inv.getInvoiceNumber()));
                if (MoneyUtils.isPositive(tax)) {
                    Account gstInput = postingAccountResolver.resolve(PostingRole.GST_INPUT);
                    jeLines.add(new PostJournalRequest.LineRequest(gstInput.getId(), tax, BigDecimal.ZERO,
                            "GST input - " + inv.getInvoiceNumber()));
                }
                jeLines.add(new PostJournalRequest.LineRequest(payable.getId(), BigDecimal.ZERO, gross,
                        "Payable - " + inv.getInvoiceNumber()));
            }
            case SALES, SERVICE -> {
                // Reached only when the project names no client, so there is no customer to
                // raise an AR invoice against. The receivable is posted straight to the ledger
                // and stays out of AR aging until a client is set.
                Account receivable = postingAccountResolver.resolve(PostingRole.ACCOUNTS_RECEIVABLE);
                Account revenue = postingAccountResolver.resolve(PostingRole.DEFAULT_REVENUE);
                jeLines.add(new PostJournalRequest.LineRequest(receivable.getId(), gross, BigDecimal.ZERO,
                        "Receivable - " + inv.getInvoiceNumber()));
                jeLines.add(new PostJournalRequest.LineRequest(revenue.getId(), BigDecimal.ZERO, net,
                        "Revenue - " + inv.getInvoiceNumber()));
                if (MoneyUtils.isPositive(tax)) {
                    Account gstOutput = postingAccountResolver.resolve(PostingRole.GST_OUTPUT);
                    jeLines.add(new PostJournalRequest.LineRequest(gstOutput.getId(), BigDecimal.ZERO, tax,
                            "GST output - " + inv.getInvoiceNumber()));
                }
            }
        }

        PostJournalRequest jeReq = new PostJournalRequest(
                inv.getIssueDate(),
                "Construction invoice " + inv.getInvoiceNumber(),
                inv.getInvoiceNumber(),
                jeLines);

        JournalEntry je = postingService.postInternal(jeReq, SOURCE_TYPE, inv.getId());
        inv.setJournalEntryId(je.getId());
        markApproved(inv);

        log.info("Approved construction invoice {} (JE: {})", inv.getInvoiceNumber(), je.getEntryNumber());
    }

    /** Moves the invoice to APPROVED and records who approved it and when. */
    private void markApproved(ConstructionInvoice inv) {
        inv.setStatus(ConstructionInvoiceStatus.APPROVED);
        inv.setApprovedBy(userContextService.getCurrentUserId());
        inv.setApprovedAt(Instant.now());
    }

    /** Whether the invoice type bills a customer rather than recording a supplier's bill. */
    private boolean isReceivableType(ConstructionInvoiceType type) {
        return type == ConstructionInvoiceType.SALES || type == ConstructionInvoiceType.SERVICE;
    }

    /**
     * The active customer the invoice's project is billed to, or null when the project has no
     * client set. A client that no longer resolves in the tenant, or has been deactivated, is
     * also treated as absent: approval falls back to the direct receivable posting rather than
     * failing, and says so in the log.
     */
    private Customer resolveProjectClient(ConstructionInvoice inv) {
        if (inv.getProjectId() == null) {
            return null;
        }
        UUID customerId = projectRepository
                .findByIdAndOrganization_Id(inv.getProjectId(), TenantContext.getCurrentOrgId())
                .map(Project::getCustomerId)
                .orElse(null);
        if (customerId == null) {
            return null;
        }
        Customer customer = customerRepository.findScopedById(customerId).orElse(null);
        if (customer == null || !customer.isActive()) {
            log.warn("Project {} names customer {} as its client but it is missing or inactive; "
                            + "posting the receivable for construction invoice {} directly",
                    inv.getProjectId(), customerId, inv.getInvoiceNumber());
            return null;
        }
        return customer;
    }

    /**
     * Raises and issues the AR invoice that carries this invoice's receivable, and adopts its
     * journal entry. The AR document repeats the construction lines against the resolved default
     * revenue account, so the two totals agree to the paisa, and the receivable is then tracked
     * in one place: AR aging, customer statements, and ordinary receipts all see it.
     */
    private void materializeArInvoice(ConstructionInvoice inv, Customer client) {
        Account revenue = postingAccountResolver.resolve(PostingRole.DEFAULT_REVENUE);

        List<CreateInvoiceRequest.LineRequest> arLines = new ArrayList<>();
        for (ConstructionInvoiceLine line : inv.getLines()) {
            arLines.add(toArLine(line, revenue.getId()));
        }

        CreateInvoiceRequest req = new CreateInvoiceRequest(
                client.getId(),
                inv.getIssueDate(),
                inv.getDueDate(),
                "Raised from construction invoice " + inv.getInvoiceNumber(),
                arLines);

        InvoiceDto issued = invoiceService.issue(invoiceService.createDraft(req).id());
        inv.setArInvoiceId(issued.id());
        inv.setJournalEntryId(issued.journalEntryId());
    }

    /**
     * Maps one construction line onto an AR invoice line. An AR line has no discount of its own,
     * so a discounted line is billed as a single unit priced at its net-of-discount amount; that
     * keeps the AR line's tax and total identical to the construction line's rather than letting
     * the discount round differently. A line with no discount keeps its quantity and unit price.
     */
    private CreateInvoiceRequest.LineRequest toArLine(ConstructionInvoiceLine line, UUID revenueAccountId) {
        BigDecimal taxRate = MoneyUtils.normalize(line.getTaxRate());
        if (MoneyUtils.isPositive(line.getDiscountAmount())) {
            BigDecimal net = MoneyUtils.normalize(line.getSubtotal().subtract(line.getDiscountAmount()));
            return new CreateInvoiceRequest.LineRequest(
                    line.getDescription(), BigDecimal.ONE, net, taxRate, revenueAccountId);
        }
        return new CreateInvoiceRequest.LineRequest(
                line.getDescription(), line.getQuantity(), line.getUnitPrice(), taxRate, revenueAccountId);
    }

    /**
     * Cancel an invoice. An approved invoice with a posted entry and no payments has that
     * entry reversed via the ledger; anything with a payment applied is refused. Where the
     * approval materialized an AR invoice, that invoice is cancelled instead, which reverses
     * the same entry and takes the receivable back out of AR aging. The status moves to
     * CANCELLED.
     */
    @Transactional
    public ConstructionInvoiceDto cancel(UUID id, String reason) {
        ConstructionInvoice inv = requireInvoice(id);

        if (inv.getStatus() == ConstructionInvoiceStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Construction invoice " + inv.getInvoiceNumber() + " is already cancelled");
        }
        if (MoneyUtils.isPositive(inv.getPaidAmount())) {
            throw new InvalidRequestException(
                    "Cannot cancel construction invoice " + inv.getInvoiceNumber()
                            + "; it has payments applied");
        }

        if (inv.getArInvoiceId() != null) {
            // The entry belongs to the AR invoice, so unwind it from that side: cancelling the
            // AR invoice reverses the entry and refuses if a receipt has already been applied.
            // The internal call is the one that skips the AR module's own refusal to cancel an
            // invoice a construction invoice raised, which is this cancellation.
            InvoiceDto cancelledAr = invoiceService.cancelInternal(inv.getArInvoiceId(), reason);
            inv.setReversalJournalEntryId(cancelledAr.reversalJournalEntryId());
        } else if (inv.getStatus() == ConstructionInvoiceStatus.APPROVED && inv.getJournalEntryId() != null) {
            JournalEntry reversal = journalRepo.findByIdWithLines(inv.getJournalEntryId())
                    .map(je -> postingService.reverse(je.getId(), new ReverseJournalRequest(reason)).id())
                    .map(reversalId -> journalRepo.findScopedById(reversalId).orElseThrow())
                    .orElseThrow(() -> new InvalidRequestException(
                            "The original journal entry for construction invoice "
                                    + inv.getInvoiceNumber() + " was not found"));
            inv.setReversalJournalEntryId(reversal.getId());
        }

        inv.setStatus(ConstructionInvoiceStatus.CANCELLED);
        log.info("Cancelled construction invoice {}: {}", inv.getInvoiceNumber(), reason);
        return mapper.toDto(inv);
    }

    /**
     * Record a payment against an approved invoice, advancing the paid/balance amounts
     * and the separate payment-status dimension. Does not post to the ledger: the
     * construction payment voucher handles cash-movement posting in its own phase.
     */
    @Transactional
    public ConstructionInvoiceDto recordPayment(UUID id, BigDecimal amount) {
        ConstructionInvoice inv = requireInvoice(id);
        if (inv.getStatus() != ConstructionInvoiceStatus.APPROVED) {
            throw new InvalidRequestException(
                    "Only APPROVED construction invoices can take a recorded payment; invoice "
                            + inv.getInvoiceNumber() + " is currently " + inv.getStatus());
        }
        BigDecimal payment = MoneyUtils.normalize(amount);
        if (!MoneyUtils.isPositive(payment)) {
            throw new InvalidRequestException("Payment amount must be positive");
        }
        BigDecimal newPaid = MoneyUtils.normalize(inv.getPaidAmount().add(payment));
        if (newPaid.compareTo(inv.getTotalAmount()) > 0) {
            throw new InvalidRequestException(
                    "Payment exceeds the balance due on construction invoice " + inv.getInvoiceNumber());
        }
        BigDecimal balance = MoneyUtils.normalize(inv.getTotalAmount().subtract(newPaid));

        inv.setPaidAmount(newPaid);
        inv.setBalanceAmount(balance);
        inv.setPaymentStatus(MoneyUtils.isZero(balance)
                ? ConstructionPaymentStatus.PAID
                : ConstructionPaymentStatus.PARTIALLY_PAID);
        inv.setPaymentRecordedBy(userContextService.getCurrentUserId());

        log.info("Recorded payment of {} on construction invoice {} (balance {})",
                payment, inv.getInvoiceNumber(), balance);
        return mapper.toDto(inv);
    }

    private ConstructionInvoice requireInvoice(UUID id) {
        return invoiceRepo.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction invoice with ID " + id + " was not found"));
    }

    /**
     * Rebuilds the invoice lines from the request and recomputes every money total.
     * Per line: subtotal = quantity * unitPrice; discount = subtotal * discountRate%;
     * tax = (subtotal - discount) * taxRate% (tax charged on the net-of-discount base);
     * total = subtotal + tax - discount. The invoice header sums the line values;
     * balance = total - paid (paid is always zero in this increment).
     */
    private void applyLinesAndTotals(ConstructionInvoice inv, List<ConstructionInvoiceLineRequest> lineReqs) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;

        for (ConstructionInvoiceLineRequest lr : lineReqs) {
            BigDecimal qty = MoneyUtils.normalize(lr.quantity());
            BigDecimal price = MoneyUtils.normalize(lr.unitPrice());
            BigDecimal lineSub = MoneyUtils.normalize(qty.multiply(price));

            BigDecimal discRate = MoneyUtils.normalize(lr.discountRate());
            BigDecimal discAmt = MoneyUtils.normalize(lineSub.multiply(discRate).divide(HUNDRED));

            BigDecimal taxRate = MoneyUtils.normalize(lr.taxRate());
            BigDecimal taxableBase = MoneyUtils.normalize(lineSub.subtract(discAmt));
            BigDecimal taxAmt = MoneyUtils.normalize(taxableBase.multiply(taxRate).divide(HUNDRED));

            BigDecimal lineTotal = MoneyUtils.normalize(lineSub.add(taxAmt).subtract(discAmt));

            ConstructionInvoiceLine line = new ConstructionInvoiceLine();
            line.setDescription(lr.description());
            line.setQuantity(qty);
            line.setUnit(lr.unit());
            line.setUnitPrice(price);
            line.setTaxRate(taxRate);
            line.setTaxAmount(taxAmt);
            line.setDiscountRate(discRate);
            line.setDiscountAmount(discAmt);
            line.setSubtotal(lineSub);
            line.setTotal(lineTotal);
            line.setInventoryItemId(lr.inventoryItemId());
            line.setAssetId(lr.assetId());
            line.setTaskId(lr.taskId());
            line.setCostCategory(resolveCostCategory(lr.costCategoryId()));
            inv.addLine(line);

            subtotal = subtotal.add(lineSub);
            taxTotal = taxTotal.add(taxAmt);
            discountTotal = discountTotal.add(discAmt);
        }

        BigDecimal total = MoneyUtils.normalize(subtotal.add(taxTotal).subtract(discountTotal));
        BigDecimal paid = MoneyUtils.normalize(inv.getPaidAmount());

        inv.setSubtotal(MoneyUtils.normalize(subtotal));
        inv.setTaxAmount(MoneyUtils.normalize(taxTotal));
        inv.setDiscountAmount(MoneyUtils.normalize(discountTotal));
        inv.setTotalAmount(total);
        inv.setPaidAmount(paid);
        inv.setBalanceAmount(MoneyUtils.normalize(total.subtract(paid)));
    }

    /**
     * Resolves a line's budget head by id within the current tenant, or null when the line carries no
     * head. A non-null id that does not resolve in the tenant is rejected, so a line can only be tagged
     * to a cost category the org actually owns.
     */
    private CostCategory resolveCostCategory(UUID costCategoryId) {
        if (costCategoryId == null) {
            return null;
        }
        return costCategoryRepository.findScopedById(costCategoryId)
                .orElseThrow(() -> new InvalidRequestException(
                        "Cost category with ID " + costCategoryId + " was not found"));
    }
}
