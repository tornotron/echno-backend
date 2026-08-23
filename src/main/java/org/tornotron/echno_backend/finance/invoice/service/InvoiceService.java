package org.tornotron.echno_backend.finance.invoice.service;

import org.tornotron.echno_backend.common.multitenancy.TenantContext;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;
import org.tornotron.echno_backend.finance.invoice.domain.InvoiceLine;
import org.tornotron.echno_backend.finance.invoice.dtos.CreateInvoiceRequest;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.mapper.InvoiceMapper;
import org.tornotron.echno_backend.finance.invoice.repositories.InvoiceRepository;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.ReverseJournalRequest;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;

import java.math.BigDecimal;
import java.util.*;

/**
 * Sales-invoice lifecycle: draft creation, issuing to the ledger, and cancellation.
 *
 * <p>An invoice starts as a DRAFT whose money totals (subtotal, tax, total) are computed from its
 * line items on save. Issuing it posts a balancing journal entry (debit Accounts Receivable, credit
 * the revenue accounts and any GST output payable) and moves it to ISSUED. Cancellation reverses that
 * journal entry when the invoice is unpaid; once any payment has been applied the invoice can no
 * longer be cancelled and a credit note is required instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepo;
    private final CustomerRepository customerRepo;
    private final AccountRepository accountRepo;
    private final JournalEntryRepository journalRepo;
    private final JournalPostingService postingService;
    private final EntryNumberGenerator numberGen;
    private final InvoiceMapper mapper;
    private final InvoicePostingProperties postingProps;
    private final TenantEntityHelper tenantEntityHelper;

    /**
     * Retrieves a single invoice with its line items.
     *
     * @param id The id of the invoice.
     * @return The invoice as a DTO.
     * @throws ResourceNotFoundException if no invoice with the given id exists.
     */
    @Transactional(readOnly = true)
    public InvoiceDto findById(UUID id) {
        return mapper.toDto(invoiceRepo.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice with ID " + id + " was not found")));
    }

    /**
     * Creates a DRAFT invoice, computing line and header totals from the request.
     *
     * <p>Each line's subtotal is quantity times unit price, its tax is that subtotal times the tax
     * rate, and the invoice subtotal, tax total, and total are summed from the lines. The customer
     * must be active and every referenced revenue account must be an active INCOME account. No ledger
     * entry is posted at this stage.
     *
     * @param req The customer, dates, notes, and line items for the invoice.
     * @return The saved draft as a DTO.
     * @throws ResourceNotFoundException if the customer does not exist.
     * @throws AccountNotFoundException if a line references an unknown revenue account.
     * @throws InvalidJournalException if the due date precedes the invoice date, the customer is inactive, or a revenue account is not an active INCOME account.
     */
    @Transactional
    public InvoiceDto createDraft(CreateInvoiceRequest req) {
        if (req.dueDate().isBefore(req.invoiceDate())) {
            throw new InvalidJournalException("Due date cannot be before invoice date");
        }

        Customer customer = customerRepo.findScopedById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer with ID " + req.customerId() + " was not found"));
        if (!customer.isActive()) {
            throw new InvalidJournalException("Customer '" + customer.getCode() + "' is inactive and cannot be invoiced");
        }

        // Pre-fetch revenue accounts
        List<UUID> accountIds = req.lines().stream().map(l -> l.revenueAccountId()).distinct().toList();
        Map<UUID, Account> accountMap = new HashMap<>();
        for (Account a : accountRepo.findAllById(accountIds)) accountMap.put(a.getId(), a);

        Invoice inv = new Invoice();
        inv.setInvoiceNumber(numberGen.next("INV"));
        inv.setCustomer(customer);
        inv.setInvoiceDate(req.invoiceDate());
        inv.setDueDate(req.dueDate());
        inv.setStatus(InvoiceStatus.DRAFT);
        inv.setNotes(req.notes());
        inv.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;

        for (var lr : req.lines()) {
            Account acc = accountMap.get(lr.revenueAccountId());
            if (acc == null) throw new AccountNotFoundException(lr.revenueAccountId());
            if (acc.getType() != AccountType.INCOME) {
                throw new InvalidJournalException(
                        "Account '" + acc.getCode() + "' must be of type INCOME to be used as a revenue account");
            }
            if (!acc.isActive()) {
                throw new InvalidJournalException("Account '" + acc.getCode() + "' is inactive and cannot be used");
            }

            BigDecimal qty   = MoneyUtils.normalize(lr.quantity());
            BigDecimal price = MoneyUtils.normalize(lr.unitPrice());
            BigDecimal lineSub = MoneyUtils.normalize(qty.multiply(price));
            BigDecimal rate  = MoneyUtils.normalize(lr.taxRate());
            BigDecimal taxAmt = MoneyUtils.normalize(
                    lineSub.multiply(rate).divide(BigDecimal.valueOf(100)));
            BigDecimal lineTotal = MoneyUtils.normalize(lineSub.add(taxAmt));

            InvoiceLine line = new InvoiceLine();
            line.setDescription(lr.description());
            line.setQuantity(qty);
            line.setUnitPrice(price);
            line.setLineSubtotal(lineSub);
            line.setTaxRate(rate);
            line.setTaxAmount(taxAmt);
            line.setLineTotal(lineTotal);
            line.setRevenueAccount(acc);
            inv.addLine(line);

            subtotal = subtotal.add(lineSub);
            taxTotal = taxTotal.add(taxAmt);
        }

        inv.setSubtotal(MoneyUtils.normalize(subtotal));
        inv.setTaxTotal(MoneyUtils.normalize(taxTotal));
        inv.setTotal(MoneyUtils.normalize(subtotal.add(taxTotal)));

        Invoice saved = invoiceRepo.save(inv);
        log.info("Created draft invoice {}", saved.getInvoiceNumber());
        return mapper.toDto(saved);
    }

    /**
     * Issues a draft invoice: posts its journal entry and transitions DRAFT to ISSUED.
     *
     * <p>The journal entry debits Accounts Receivable for the invoice total, credits each revenue
     * account (grouped, by line subtotal), and credits GST Output Payable for any tax. The resulting
     * entry id is stored on the invoice.
     *
     * @param invoiceId The id of the draft invoice to issue.
     * @return The issued invoice as a DTO.
     * @throws ResourceNotFoundException if the invoice does not exist.
     * @throws AccountNotFoundException if the Accounts Receivable or GST output account is not configured.
     * @throws InvalidJournalException if the invoice is not in DRAFT status.
     */
    @Transactional
    public InvoiceDto issue(UUID invoiceId) {
        Invoice inv = invoiceRepo.findByIdWithLines(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice with ID " + invoiceId + " was not found"));
        if (inv.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvalidJournalException(
                    "Only DRAFT invoices can be issued; invoice " + inv.getInvoiceNumber()
                            + " is currently " + inv.getStatus());
        }

        Account ar = accountRepo.findByCodeAndOrganization_Id(postingProps.getArAccountCode(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new AccountNotFoundException(postingProps.getArAccountCode()));

        List<PostJournalRequest.LineRequest> jeLines = new ArrayList<>();

        // DR Accounts Receivable for total
        jeLines.add(new PostJournalRequest.LineRequest(ar.getId(), inv.getTotal(), BigDecimal.ZERO,
                "Receivable - " + inv.getInvoiceNumber()));

        // CR each revenue account, grouped by account
        Map<UUID, BigDecimal> revenueGrouped = new LinkedHashMap<>();
        for (InvoiceLine line : inv.getLines()) {
            revenueGrouped.merge(line.getRevenueAccount().getId(),
                    line.getLineSubtotal(), BigDecimal::add);
        }
        for (var entry : revenueGrouped.entrySet()) {
            jeLines.add(new PostJournalRequest.LineRequest(entry.getKey(),
                    BigDecimal.ZERO, MoneyUtils.normalize(entry.getValue()),
                    "Revenue - " + inv.getInvoiceNumber()));
        }

        // CR GST Output Payable (if applicable)
        if (MoneyUtils.isPositive(inv.getTaxTotal())) {
            Account gstOut = accountRepo.findByCodeAndOrganization_Id(postingProps.getGstOutputCode(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new AccountNotFoundException(postingProps.getGstOutputCode()));
            jeLines.add(new PostJournalRequest.LineRequest(gstOut.getId(), BigDecimal.ZERO, inv.getTaxTotal(),
                    "GST output - " + inv.getInvoiceNumber()));
        }

        PostJournalRequest jeReq = new PostJournalRequest(
                inv.getInvoiceDate(),
                "Invoice " + inv.getInvoiceNumber() + " to " + inv.getCustomer().getName(),
                inv.getInvoiceNumber(),
                jeLines
        );

        JournalEntry je = postingService.postInternal(jeReq, "INVOICE", inv.getId());
        inv.setJournalEntryId(je.getId());
        inv.setStatus(InvoiceStatus.ISSUED);

        log.info("Issued invoice {} (JE: {})", inv.getInvoiceNumber(), je.getEntryNumber());
        return mapper.toDto(inv);
    }

    /**
     * Cancels an invoice, with behaviour depending on its current status.
     *
     * <p>A DRAFT is simply moved to CANCELLED. An ISSUED invoice with no payments has its journal
     * entry reversed and the reversal id is recorded before it moves to CANCELLED. An invoice that
     * has any payment applied cannot be cancelled; a credit note is required instead.
     *
     * @param invoiceId The id of the invoice to cancel.
     * @param reason Free-text reason recorded on the reversal entry.
     * @return The cancelled invoice as a DTO.
     * @throws ResourceNotFoundException if the invoice does not exist.
     * @throws InvalidJournalException if the invoice has payments applied, is already cancelled, or its original journal entry cannot be found.
     */
    @Transactional
    public InvoiceDto cancel(UUID invoiceId, String reason) {
        Invoice inv = invoiceRepo.findByIdWithLines(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice with ID " + invoiceId + " was not found"));

        switch (inv.getStatus()) {
            case DRAFT -> inv.setStatus(InvoiceStatus.CANCELLED);
            case ISSUED -> {
                if (MoneyUtils.isPositive(inv.getAmountPaid())) {
                    throw new InvalidJournalException(
                            "Cannot cancel invoice " + inv.getInvoiceNumber() + "; it has payments applied. Issue a credit note instead");
                }
                JournalEntry reversal = journalRepo.findByIdWithLines(inv.getJournalEntryId())
                        .map(je -> postingService.reverse(je.getId(),
                                new ReverseJournalRequest(reason)).id())
                        .map(id -> journalRepo.findScopedById(id).orElseThrow())
                        .orElseThrow(() -> new InvalidJournalException(
                                "The original journal entry for invoice " + inv.getInvoiceNumber() + " was not found"));
                inv.setReversalJournalEntryId(reversal.getId());
                inv.setStatus(InvoiceStatus.CANCELLED);
            }
            case PARTIALLY_PAID, PAID -> throw new InvalidJournalException(
                    "Cannot cancel invoice " + inv.getInvoiceNumber() + "; it has payments applied. Issue a credit note instead");
            case CANCELLED -> throw new InvalidJournalException(
                    "Invoice " + inv.getInvoiceNumber() + " is already cancelled");
        }

        log.info("Cancelled invoice {}: {}", inv.getInvoiceNumber(), reason);
        return mapper.toDto(inv);
    }
}