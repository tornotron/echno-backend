package org.tornotron.echno_backend.finance.construction.service;

import org.tornotron.echno_backend.common.multitenancy.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionPostingProperties;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoiceLine;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineRequest;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapper;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceRepository;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceSpecifications;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.ReverseJournalRequest;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
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
    private final AccountRepository accountRepo;
    private final JournalEntryRepository journalRepo;
    private final JournalPostingService postingService;
    private final ConstructionPostingProperties postingProps;
    private final InvoicePostingProperties arPostingProps;
    private final UserContextService userContextService;

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
     */
    @Transactional
    public ConstructionInvoiceDto submit(UUID id) {
        ConstructionInvoice inv = requireInvoice(id);
        if (inv.getStatus() != ConstructionInvoiceStatus.DRAFT) {
            throw new InvalidRequestException(
                    "Only DRAFT construction invoices can be submitted; invoice "
                            + inv.getInvoiceNumber() + " is currently " + inv.getStatus());
        }
        inv.setStatus(ConstructionInvoiceStatus.PENDING);
        inv.setSubmittedBy(userContextService.getCurrentUserId());
        inv.setSubmittedAt(Instant.now());
        log.info("Submitted construction invoice {} for approval", inv.getInvoiceNumber());
        return mapper.toDto(inv);
    }

    /**
     * Approve a pending invoice: PENDING -> APPROVED, and post the journal entry.
     *
     * <p>Posting is invoice-level to the configured default accounts:
     * <ul>
     *   <li>PURCHASE / EXPENSE: DR default expense (net of discount) + DR GST input
     *       (tax, if any); CR Accounts Payable (gross total).</li>
     *   <li>SALES / SERVICE: DR Accounts Receivable (gross total); CR default revenue
     *       (net of discount) + CR GST output (tax, if any).</li>
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

        BigDecimal net = MoneyUtils.normalize(inv.getSubtotal().subtract(inv.getDiscountAmount()));
        BigDecimal tax = MoneyUtils.normalize(inv.getTaxAmount());
        BigDecimal gross = MoneyUtils.normalize(inv.getTotalAmount());

        List<PostJournalRequest.LineRequest> jeLines = new ArrayList<>();
        switch (inv.getType()) {
            case PURCHASE, EXPENSE -> {
                // TODO: option A AR materialization is only for sales/service; purchase and
                // expense always post their own payable entry (no AR analog).
                Account expense = requireAccount(postingProps.getDefaultExpenseCode());
                Account payable = requireAccount(postingProps.getApAccountCode());
                jeLines.add(new PostJournalRequest.LineRequest(expense.getId(), net, BigDecimal.ZERO,
                        "Expense - " + inv.getInvoiceNumber()));
                if (MoneyUtils.isPositive(tax)) {
                    Account gstInput = requireAccount(postingProps.getGstInputCode());
                    jeLines.add(new PostJournalRequest.LineRequest(gstInput.getId(), tax, BigDecimal.ZERO,
                            "GST input - " + inv.getInvoiceNumber()));
                }
                jeLines.add(new PostJournalRequest.LineRequest(payable.getId(), BigDecimal.ZERO, gross,
                        "Payable - " + inv.getInvoiceNumber()));
            }
            case SALES, SERVICE -> {
                // TODO: option A AR materialization - the preferred sales/service path
                // materializes a real AR Invoice (customer = project client) via
                // InvoiceService.issue so AR aging and receipts pick it up. Until customer
                // resolution exists this posts the AR journal entry directly instead.
                Account receivable = requireAccount(arPostingProps.getArAccountCode());
                Account revenue = requireAccount(postingProps.getDefaultRevenueCode());
                jeLines.add(new PostJournalRequest.LineRequest(receivable.getId(), gross, BigDecimal.ZERO,
                        "Receivable - " + inv.getInvoiceNumber()));
                jeLines.add(new PostJournalRequest.LineRequest(revenue.getId(), BigDecimal.ZERO, net,
                        "Revenue - " + inv.getInvoiceNumber()));
                if (MoneyUtils.isPositive(tax)) {
                    Account gstOutput = requireAccount(arPostingProps.getGstOutputCode());
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
        inv.setStatus(ConstructionInvoiceStatus.APPROVED);
        inv.setApprovedBy(userContextService.getCurrentUserId());
        inv.setApprovedAt(Instant.now());

        log.info("Approved construction invoice {} (JE: {})", inv.getInvoiceNumber(), je.getEntryNumber());
        return mapper.toDto(inv);
    }

    /**
     * Cancel an invoice. An approved invoice with a posted entry and no payments has that
     * entry reversed via the ledger; anything with a payment applied is refused. The
     * status moves to CANCELLED.
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

        if (inv.getStatus() == ConstructionInvoiceStatus.APPROVED && inv.getJournalEntryId() != null) {
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

    private Account requireAccount(String code) {
        return accountRepo.findByCodeAndOrganization_Id(code, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new AccountNotFoundException(code));
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
}
