package org.tornotron.echno_backend.finance.invoice.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
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

    @Transactional(readOnly = true)
    public InvoiceDto findById(UUID id) {
        return mapper.toDto(invoiceRepo.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id)));
    }

    @Transactional
    public InvoiceDto createDraft(CreateInvoiceRequest req) {
        if (req.dueDate().isBefore(req.invoiceDate())) {
            throw new InvalidJournalException("Due date cannot be before invoice date");
        }

        Customer customer = customerRepo.findById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + req.customerId()));
        if (!customer.isActive()) {
            throw new InvalidJournalException("Customer is inactive: " + customer.getCode());
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

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;

        for (var lr : req.lines()) {
            Account acc = accountMap.get(lr.revenueAccountId());
            if (acc == null) throw new AccountNotFoundException(lr.revenueAccountId());
            if (acc.getType() != AccountType.INCOME) {
                throw new InvalidJournalException(
                        "Revenue account must be of type INCOME: " + acc.getCode());
            }
            if (!acc.isActive()) {
                throw new InvalidJournalException("Account is inactive: " + acc.getCode());
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
     * Posts the journal entry, transitions DRAFT → ISSUED.
     * JE layout:
     *   DR Accounts Receivable      (total)
     *     CR Revenue account(s)     (per line, grouped)
     *     CR GST Output Payable     (if any tax)
     */
    @Transactional
    public InvoiceDto issue(UUID invoiceId) {
        Invoice inv = invoiceRepo.findByIdWithLines(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));
        if (inv.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvalidJournalException(
                    "Only DRAFT invoices can be issued. Current: " + inv.getStatus());
        }

        Account ar = accountRepo.findByCode(postingProps.getArAccountCode())
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
            Account gstOut = accountRepo.findByCode(postingProps.getGstOutputCode())
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
     * Cancel an invoice.
     * - DRAFT: just transition to CANCELLED
     * - ISSUED with no payments: reverse the JE, transition to CANCELLED
     * - Anything paid: refuse — issue a credit note in v2 instead
     */
    @Transactional
    public InvoiceDto cancel(UUID invoiceId, String reason) {
        Invoice inv = invoiceRepo.findByIdWithLines(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        switch (inv.getStatus()) {
            case DRAFT -> inv.setStatus(InvoiceStatus.CANCELLED);
            case ISSUED -> {
                if (MoneyUtils.isPositive(inv.getAmountPaid())) {
                    throw new InvalidJournalException(
                            "Cannot cancel a paid invoice; issue a credit note instead");
                }
                JournalEntry reversal = journalRepo.findByIdWithLines(inv.getJournalEntryId())
                        .map(je -> postingService.reverse(je.getId(),
                                new ReverseJournalRequest(reason)).id())
                        .map(id -> journalRepo.findById(id).orElseThrow())
                        .orElseThrow(() -> new InvalidJournalException("Original JE not found"));
                inv.setReversalJournalEntryId(reversal.getId());
                inv.setStatus(InvoiceStatus.CANCELLED);
            }
            case PARTIALLY_PAID, PAID -> throw new InvalidJournalException(
                    "Cannot cancel a paid invoice; issue a credit note instead");
            case CANCELLED -> throw new InvalidJournalException("Invoice already cancelled");
        }

        log.info("Cancelled invoice {}: {}", inv.getInvoiceNumber(), reason);
        return mapper.toDto(inv);
    }
}