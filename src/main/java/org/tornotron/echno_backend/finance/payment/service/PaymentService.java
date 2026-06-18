package org.tornotron.echno_backend.finance.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.DuplicateIdempotencyKeyException;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;
import org.tornotron.echno_backend.finance.invoice.repositories.InvoiceRepository;
import org.tornotron.echno_backend.finance.bank.domain.CompanyBankAccount;
import org.tornotron.echno_backend.finance.bank.repositories.CompanyBankAccountRepository;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.finance.payment.domain.Payment;
import org.tornotron.echno_backend.finance.payment.domain.PaymentAllocation;
import org.tornotron.echno_backend.finance.payment.dtos.PaymentDto;
import org.tornotron.echno_backend.finance.payment.dtos.RecordPaymentRequest;
import org.tornotron.echno_backend.finance.payment.mapper.PaymentMapper;
import org.tornotron.echno_backend.finance.payment.repositories.PaymentRepository;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final CustomerRepository customerRepo;
    private final InvoiceRepository invoiceRepo;
    private final AccountRepository accountRepo;
    private final CompanyBankAccountRepository companyBankRepo;
    private final JournalPostingService postingService;
    private final EntryNumberGenerator numberGen;
    private final PaymentMapper mapper;
    private final InvoicePostingProperties postingProps;

    @Transactional(readOnly = true)
    public PaymentDto findById(UUID id) {
        return mapper.toDto(paymentRepo.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id)));
    }

    @Transactional
    public PaymentDto record(RecordPaymentRequest req, String idempotencyKey) {
        // 1. Idempotency check (returns existing payment if key was already used)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Payment> existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency hit: returning existing payment {}", existing.get().getId());
                return mapper.toDto(existing.get());
            }
        }

        // 2. Validate sum of allocations == payment amount
        BigDecimal sumAlloc = req.allocations().stream()
                .map(a -> MoneyUtils.normalize(a.allocatedAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = MoneyUtils.normalize(req.amount());

        if (sumAlloc.compareTo(amount) != 0) {
            throw new InvalidJournalException(
                    "Sum of allocations (" + sumAlloc + ") does not equal payment amount (" + amount + ")");
        }

        // 3. Load customer + company bank account
        Customer customer = customerRepo.findById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + req.customerId()));
        CompanyBankAccount companyBank = companyBankRepo.findById(req.companyBankAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Company Bank Account not found: " + req.companyBankAccountId()));

        Account bankLedger = companyBank.getLedgerAccount();
        if (bankLedger.getType() != AccountType.ASSET) {
            throw new InvalidJournalException(
                    "Payment account must be ASSET type: " + bankLedger.getCode());
        }
        if (!companyBank.isActive()) {
            throw new InvalidJournalException("Company Bank account is inactive: " + companyBank.getBankName());
        }

        // 4. Lock and validate each invoice (pessimistic write lock)
        List<Invoice> lockedInvoices = new ArrayList<>();
        Map<UUID, BigDecimal> allocByInvoice = new LinkedHashMap<>();
        for (var ar : req.allocations()) {
            allocByInvoice.merge(ar.invoiceId(),
                    MoneyUtils.normalize(ar.allocatedAmount()), BigDecimal::add);
        }

        for (var e : allocByInvoice.entrySet()) {
            Invoice inv = invoiceRepo.findByIdForUpdate(e.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + e.getKey()));
            if (!inv.getCustomer().getId().equals(customer.getId())) {
                throw new InvalidJournalException(
                        "Invoice " + inv.getInvoiceNumber() + " does not belong to customer " + customer.getCode());
            }
            if (inv.getStatus() != InvoiceStatus.ISSUED &&
                    inv.getStatus() != InvoiceStatus.PARTIALLY_PAID) {
                throw new InvalidJournalException(
                        "Invoice " + inv.getInvoiceNumber() + " is not open (status: " + inv.getStatus() + ")");
            }
            BigDecimal allocation = e.getValue();
            BigDecimal balance = inv.balanceDue();
            if (allocation.compareTo(balance) > 0) {
                throw new InvalidJournalException(
                        "Allocation " + allocation + " exceeds balance " + balance +
                                " for invoice " + inv.getInvoiceNumber());
            }
            lockedInvoices.add(inv);
        }

        // 5. Build the payment
        Payment payment = new Payment();
        payment.setPaymentNumber(numberGen.next("PAY"));
        payment.setCustomer(customer);
        payment.setPaymentDate(req.paymentDate());
        payment.setAmount(amount);
        payment.setCompanyBankAccount(companyBank);
        payment.setExternalReference(req.externalReference());
        payment.setIdempotencyKey(idempotencyKey);
        payment.setNotes(req.notes());

        for (Invoice inv : lockedInvoices) {
            BigDecimal alloc = allocByInvoice.get(inv.getId());
            PaymentAllocation pa = new PaymentAllocation();
            pa.setInvoice(inv);
            pa.setAllocatedAmount(alloc);
            payment.addAllocation(pa);

            // Update invoice balance & status
            BigDecimal newPaid = MoneyUtils.normalize(inv.getAmountPaid().add(alloc));
            inv.setAmountPaid(newPaid);
            if (newPaid.compareTo(inv.getTotal()) == 0) {
                inv.setStatus(InvoiceStatus.PAID);
            } else {
                inv.setStatus(InvoiceStatus.PARTIALLY_PAID);
            }
        }

        // 6. Post JE: DR Bank, CR Accounts Receivable
        Account ar = accountRepo.findByCode(postingProps.getArAccountCode())
                .orElseThrow(() -> new AccountNotFoundException(postingProps.getArAccountCode()));

        List<PostJournalRequest.LineRequest> jeLines = List.of(
                new PostJournalRequest.LineRequest(bankLedger.getId(), amount, BigDecimal.ZERO,
                        "Payment received - " + payment.getPaymentNumber()),
                new PostJournalRequest.LineRequest(ar.getId(), BigDecimal.ZERO, amount,
                        "AR settled - " + payment.getPaymentNumber())
        );

        PostJournalRequest jeReq = new PostJournalRequest(
                payment.getPaymentDate(),
                "Payment " + payment.getPaymentNumber() + " from " + customer.getName(),
                payment.getPaymentNumber(),
                jeLines
        );

        try {
            JournalEntry je = postingService.postInternal(jeReq, "PAYMENT", null);
            payment.setJournalEntryId(je.getId());
            Payment saved = paymentRepo.save(payment);
            log.info("Recorded payment {} (₹{}) from {} (JE: {})",
                    saved.getPaymentNumber(), amount, customer.getCode(), je.getEntryNumber());
            return mapper.toDto(saved);
        } catch (DataIntegrityViolationException ex) {
            // Race condition: another request with same idempotency key landed first
            if (idempotencyKey != null) {
                return paymentRepo.findByIdempotencyKey(idempotencyKey)
                        .map(mapper::toDto)
                        .orElseThrow(() -> new DuplicateIdempotencyKeyException(idempotencyKey, "unknown"));
            }
            throw ex;
        }
    }
    }


