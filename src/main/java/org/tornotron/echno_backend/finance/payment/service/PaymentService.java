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
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
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
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;
import org.tornotron.echno_backend.finance.payment.domain.Payment;
import org.tornotron.echno_backend.finance.payment.domain.PaymentAllocation;
import org.tornotron.echno_backend.finance.payment.dtos.PaymentDto;
import org.tornotron.echno_backend.finance.payment.dtos.RecordPaymentRequest;
import org.tornotron.echno_backend.finance.payment.mapper.PaymentMapper;
import org.tornotron.echno_backend.finance.payment.repositories.PaymentRepository;

import java.math.BigDecimal;
import java.util.*;

/**
 * Records customer payments and allocates them against outstanding invoices.
 *
 * <p>A payment carries one or more allocations whose amounts must sum to the payment total. Each
 * targeted invoice is locked for update, checked to belong to the paying customer and to be open
 * (ISSUED or PARTIALLY_PAID), and its allocation must not exceed the outstanding balance. Applying
 * the payment updates each invoice's paid amount and status, then posts a journal entry that debits
 * the bank ledger account and credits Accounts Receivable.
 *
 * <p>Recording is idempotent: a caller-supplied idempotency key that has already been used returns
 * the existing payment rather than creating a second one, and a concurrent duplicate that trips the
 * unique constraint is resolved the same way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final CustomerRepository customerRepo;
    private final InvoiceRepository invoiceRepo;
    private final CompanyBankAccountRepository companyBankRepo;
    private final JournalPostingService postingService;
    private final EntryNumberGenerator numberGen;
    private final PaymentMapper mapper;
    private final PostingAccountResolver postingAccountResolver;
    private final TenantEntityHelper tenantEntityHelper;

    /**
     * Retrieves a single payment with its allocations and related details.
     *
     * @param id The id of the payment.
     * @return The payment as a DTO.
     * @throws ResourceNotFoundException if no payment with the given id exists.
     */
    @Transactional(readOnly = true)
    public PaymentDto findById(UUID id) {
        return mapper.toDto(paymentRepo.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment with ID " + id + " was not found")));
    }

    /**
     * Records a payment, allocates it across the given invoices, and posts the bank receipt entry.
     *
     * <p>If the idempotency key was already used, the existing payment is returned unchanged. The
     * allocation amounts must sum to the payment amount. Each invoice is locked, validated (belongs
     * to the customer, still open, allocation within its balance), and has its paid amount and status
     * updated. The posted journal entry debits the company bank ledger account and credits Accounts
     * Receivable for the payment total.
     *
     * @param req The customer, payment date, amount, bank account, and per-invoice allocations.
     * @param idempotencyKey Optional key that makes a repeated request return the first payment; may be null or blank.
     * @return The recorded (or already-existing) payment as a DTO.
     * @throws ResourceNotFoundException if the customer, company bank account, or an allocated invoice does not exist.
     * @throws AccountNotFoundException if the Accounts Receivable account is not configured.
     * @throws InvalidJournalException if the allocations do not sum to the amount, the bank account is not an active ASSET account, or an invoice is not open, belongs to another customer, or is over-allocated.
     * @throws DuplicateIdempotencyKeyException if a concurrent request with the same key wins the race and the existing payment cannot be re-read.
     */
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
                    "Sum of allocations (" + sumAlloc + ") does not equal the payment amount (" + amount + ")");
        }

        // 3. Load customer + company bank account
        Customer customer = customerRepo.findScopedById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer with ID " + req.customerId() + " was not found"));
        CompanyBankAccount companyBank = companyBankRepo.findScopedById(req.companyBankAccountId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Company bank account with ID " + req.companyBankAccountId() + " was not found"));

        Account bankLedger = companyBank.getLedgerAccount();
        if (bankLedger.getType() != AccountType.ASSET) {
            throw new InvalidJournalException(
                    "Payment account '" + bankLedger.getCode() + "' must be of type ASSET");
        }
        if (!companyBank.isActive()) {
            throw new InvalidJournalException(
                    "Company bank account '" + companyBank.getBankName() + "' is inactive and cannot be used");
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
                    .orElseThrow(() -> new ResourceNotFoundException("Invoice with ID " + e.getKey() + " was not found"));
            if (!inv.getCustomer().getId().equals(customer.getId())) {
                throw new InvalidJournalException(
                        "Invoice " + inv.getInvoiceNumber() + " does not belong to customer " + customer.getCode());
            }
            if (inv.getStatus() != InvoiceStatus.ISSUED &&
                    inv.getStatus() != InvoiceStatus.PARTIALLY_PAID) {
                throw new InvalidJournalException(
                        "Invoice " + inv.getInvoiceNumber() + " is not open for payment (status: " + inv.getStatus() + ")");
            }
            BigDecimal allocation = e.getValue();
            BigDecimal balance = inv.balanceDue();
            if (allocation.compareTo(balance) > 0) {
                throw new InvalidJournalException(
                        "Allocation of " + allocation + " exceeds the outstanding balance of " + balance
                                + " for invoice " + inv.getInvoiceNumber());
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
        payment.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

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
        Account ar = postingAccountResolver.resolve(PostingRole.ACCOUNTS_RECEIVABLE);

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


