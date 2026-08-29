package org.tornotron.echno_backend.finance.invoice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;
import org.tornotron.echno_backend.finance.invoice.domain.InvoiceLine;
import org.tornotron.echno_backend.finance.invoice.dtos.CreateInvoiceRequest;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.mapper.InvoiceMapper;
import org.tornotron.echno_backend.finance.invoice.repositories.InvoiceRepository;
import org.tornotron.echno_backend.finance.invoice.repositories.InvoiceSpecifications;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceRepository;
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
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;

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
 *
 * <p>An invoice raised by another document (a sales or service construction invoice materializes
 * one on approval) belongs to that document's lifecycle: {@link #cancel} refuses it and the source
 * document is cancelled instead, which unwinds both sides through {@link #cancelInternal}.
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
    private final PostingAccountResolver postingAccountResolver;
    private final TenantEntityHelper tenantEntityHelper;
    private final ConstructionInvoiceRepository constructionInvoiceRepo;

    /**
     * The order every page of the listing is read in.
     *
     * <p>A page with no order is a page in whatever order the storage engine happened to produce,
     * which on a distributed engine is not stable between two requests: the same row can appear on
     * page one and again on page two while another never appears at all. Newest invoice first is
     * what a receivables screen wants; the invoice number breaks the tie between two invoices dated
     * the same day, and because it is zero padded within a fiscal year it orders by issue sequence.
     */
    private static final Sort LIST_ORDER =
            Sort.by(Sort.Order.desc("invoiceDate"), Sort.Order.desc("invoiceNumber"));

    /**
     * Lists invoices in the current tenant, newest first, one page at a time.
     *
     * <p>Every filter is optional and they combine with AND. Scoping to the caller's organization
     * is not one of them: it comes from the Hibernate {@code orgFilter} on the transaction, which
     * applies to the criteria query and to the count behind {@code getTotalElements} alike, so a
     * member of one tenant cannot enumerate another's invoices and cannot learn how many there are
     * either.
     *
     * @param customerId Restrict to one customer, or null for every customer.
     * @param status     Restrict to one lifecycle status, or null for every status.
     * @param openOnly   Restrict to invoices still owed (ISSUED or PARTIALLY_PAID) when true.
     * @param pageNo     Zero-based page index.
     * @param pageSize   Rows per page.
     * @return The requested page of invoices, each with its line items.
     */
    @Transactional(readOnly = true)
    public Page<InvoiceDto> findAll(UUID customerId, InvoiceStatus status, boolean openOnly,
                                    int pageNo, int pageSize) {
        return invoiceRepo.findAll(
                        InvoiceSpecifications.withFilters(customerId, status, openOnly),
                        PageRequest.of(pageNo, pageSize, LIST_ORDER))
                .map(mapper::toDto);
    }

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

        Account ar = postingAccountResolver.resolve(PostingRole.ACCOUNTS_RECEIVABLE);

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
            Account gstOut = postingAccountResolver.resolve(PostingRole.GST_OUTPUT);
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
     * <p>An invoice another document raised for itself is refused here: its source document owns
     * the journal entry they share, so cancelling this invoice on its own would leave that document
     * approved against a reversed entry. Cancelling the source unwinds both sides.
     *
     * @param invoiceId The id of the invoice to cancel.
     * @param reason Free-text reason recorded on the reversal entry.
     * @return The cancelled invoice as a DTO.
     * @throws ResourceNotFoundException if the invoice does not exist.
     * @throws InvalidJournalException if the invoice has payments applied, is already cancelled, was raised by another document, or its original journal entry cannot be found.
     */
    @Transactional
    public InvoiceDto cancel(UUID invoiceId, String reason) {
        constructionInvoiceRepo.findByArInvoiceId(invoiceId).ifPresent(source -> {
            throw new InvalidJournalException(
                    "Invoice was raised for construction invoice " + source.getInvoiceNumber()
                            + " and cannot be cancelled on its own; cancel construction invoice "
                            + source.getInvoiceNumber() + " instead");
        });
        return cancelInternal(invoiceId, reason);
    }

    /**
     * Cancels an invoice without the check on who raised it.
     *
     * <p>The document that materialized an invoice uses this to unwind it as part of cancelling
     * itself; every other caller goes through {@link #cancel}. The payment guard stays in force
     * here, so a receipt already applied on this side still blocks the cancellation, whichever
     * document it was asked for.
     *
     * @param invoiceId The id of the invoice to cancel.
     * @param reason Free-text reason recorded on the reversal entry.
     * @return The cancelled invoice as a DTO.
     * @throws ResourceNotFoundException if the invoice does not exist.
     * @throws InvalidJournalException if the invoice has payments applied, is already cancelled, or its original journal entry cannot be found.
     */
    @Transactional
    public InvoiceDto cancelInternal(UUID invoiceId, String reason) {
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