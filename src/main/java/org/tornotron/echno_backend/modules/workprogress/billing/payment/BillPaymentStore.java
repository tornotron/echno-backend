package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillEventType;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillPaymentState;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBill;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBillEvent;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.BillPaymentDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.BillPaymentsDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.RaiseBillPaymentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.RecordBillPaymentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGateways;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutCommand;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutLookup;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutResult;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBillEventRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBillRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport;
import org.tornotron.echno_backend.modules.workprogress.time.WorkProgressClock;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.payable.PayableRepository;
import org.tornotron.echno_backend.payable.PayableService;
import org.tornotron.echno_backend.subcontract.SubContract;

/**
 * The state of a bill's payments, one short transaction at a time. {@link BillPaymentService} calls
 * the provider between these, never inside one, so no database lock is held across a network call and
 * an answer that gets lost leaves a row saying what is known.
 *
 * <p>The payable is the record of what was paid. Raising and authorizing check against it under its row
 * lock, which is also what keeps a bill to one payment in flight; settling and reversing go through
 * {@link PayableService}, the same way Finance records a payment.
 */
@Slf4j
@Component
public class BillPaymentStore {

    private static final String PROVIDER_ACTOR = "Payment provider";

    private final ContractBillPaymentRepository payments;
    private final ContractBillRepository bills;
    private final ContractBillEventRepository events;
    private final PayableRepository payableRepository;
    private final PayableService payables;
    private final BillPaymentGateways gateways;
    private final BillingSupport support;
    private final Clock clock;

    public BillPaymentStore(ContractBillPaymentRepository payments, ContractBillRepository bills,
                            ContractBillEventRepository events, PayableRepository payableRepository,
                            PayableService payables, BillPaymentGateways gateways, BillingSupport support,
                            @WorkProgressClock Clock clock) {
        this.payments = payments;
        this.bills = bills;
        this.events = events;
        this.payableRepository = payableRepository;
        this.payables = payables;
        this.gateways = gateways;
        this.support = support;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public BillPaymentsDto view(UUID billId) {
        Long orgId = TenantContext.getCurrentOrgId();
        ContractBill bill = requireBill(billId, orgId);
        SubContract contract = support.requireContract(bill.getSubContractId(), orgId);
        Payable payable = bill.getPayableId() == null ? null
                : payableRepository.findByIdAndOrganization_Id(bill.getPayableId(), orgId).orElse(null);
        List<BillPaymentDto> rows = payments.findForBill(orgId, billId).stream().map(p -> toDto(p, orgId)).toList();
        return new BillPaymentsDto(bill.getId(), bill.getBillNumber(), gateways.providerFor(orgId),
                bill.getPayableId(), payable == null ? null : payable.getAmountRecorded(),
                payable == null ? null : paid(payable), payable == null ? null : payable.getAmountDue(),
                paymentState(bill, payable), rows.stream().anyMatch(row -> row.status().isInFlight()),
                hasBeneficiary(contract), rows);
    }

    /** What a payment is and where it stands, for deciding what to do next outside a transaction. */
    @Transactional(readOnly = true)
    public PaymentStep step(UUID paymentId) {
        ContractBillPayment payment = require(paymentId);
        return new PaymentStep(payment.getBillId(), payment.getStatus(), payment.getProvider());
    }

    public record PaymentStep(UUID billId, BillPaymentStatus status, BillPaymentProvider provider) {}

    @Transactional(readOnly = true)
    public PayoutCommand commandFor(UUID paymentId) {
        ContractBillPayment payment = require(paymentId);
        Long orgId = TenantContext.getCurrentOrgId();
        ContractBill bill = requireBill(payment.getBillId(), orgId);
        SubContract contract = support.requireContract(bill.getSubContractId(), orgId);
        requireBeneficiary(contract);
        return new PayoutCommand(payment.getId(), payment.getAmount(),
                BillingSupport.contractRef(contract) + " " + bill.getBillNumber(), contract.getContractorName(),
                contract.getContractorEmail(), contract.getContractorPhone(), contract.getBankAccountNumber().trim(),
                contract.getBankIfsc().trim());
    }

    @Transactional(readOnly = true)
    public PayoutLookup lookupFor(UUID paymentId) {
        ContractBillPayment payment = require(paymentId);
        return new PayoutLookup(payment.getId(), payment.getProviderRef(), payment.getAmount());
    }

    // ---------------------------------------------------------------- the two signatures

    /**
     * Raises a payment on an approved bill.
     *
     * @throws InvalidRequestException if the bill is not approved, nothing is due, the amount is more
     *         than is due, a payment is already in flight, or a provider would have no one to pay
     */
    @Transactional
    public void raise(UUID billId, RaiseBillPaymentRequest req, Long requester) {
        Long orgId = TenantContext.getCurrentOrgId();
        ContractBill bill = requireBill(billId, orgId);
        if (bill.getStatus() != BillStatus.APPROVED) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is "
                    + bill.getStatus().name().toLowerCase() + "; only an approved bill can be paid");
        }
        if (bill.getPayableId() == null) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " came to nothing payable");
        }
        if (requester == null) {
            throw new InvalidRequestException("This session resolves to no user of the organization, and a "
                    + "payment has to say who raised it");
        }
        // The row lock serializes two people raising at once, and a raise against a payment being recorded.
        Payable payable = lockPayable(bill, orgId);
        if (payments.existsForBillInStatus(orgId, billId, BillPaymentStatus.IN_FLIGHT)) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber()
                    + " already has a payment in progress; finish or reject that one first");
        }
        BigDecimal amount = BillPaymentAmounts.money(req.amount());
        requireWithinDue(bill, payable, amount);

        BillPaymentProvider provider = gateways.providerFor(orgId);
        ContractBillPayment payment = new ContractBillPayment();
        payment.setOrganization(bill.getOrganization());
        payment.setBillId(billId);
        payment.setPayableId(payable.getId());
        payment.setAmount(amount);
        payment.setStatus(BillPaymentStatus.REQUESTED);
        payment.setProvider(provider);
        payment.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        payment.setRequestedBy(requester);
        payment.setRequestedAt(now());
        if (provider != BillPaymentProvider.MANUAL) {
            SubContract contract = support.requireContract(bill.getSubContractId(), orgId);
            requireBeneficiary(contract);
            String account = contract.getBankAccountNumber().trim();
            payment.setBeneficiaryName(contract.getContractorName());
            payment.setBeneficiaryIfsc(contract.getBankIfsc().trim());
            payment.setBeneficiaryAccountLast4(account.substring(Math.max(0, account.length() - 4)));
        }
        ContractBillPayment saved = payments.save(payment);
        record(bill, BillEventType.PAYMENT_REQUESTED, label(saved) + " of " + rupees(amount) + " raised"
                + (amount.compareTo(payable.getAmountDue()) < 0 ? " as a part payment" : ""), requester);
    }

    /**
     * Gives a requested payment its second signature. A system admin who raised it may authorize it
     * themselves, and that is recorded as such.
     *
     * @throws InvalidRequestException if it is not waiting for a signature or is now more than is due
     */
    @Transactional
    public void authorize(UUID paymentId, Long authorizer) {
        Long orgId = TenantContext.getCurrentOrgId();
        ContractBillPayment payment = lock(paymentId, orgId);
        if (payment.getStatus() != BillPaymentStatus.REQUESTED) {
            throw new InvalidRequestException("This payment is " + statusText(payment)
                    + "; only a requested payment can be authorized");
        }
        if (authorizer == null) {
            throw new InvalidRequestException("This session resolves to no user of the organization, and an "
                    + "authorization has to say who gave it");
        }
        ContractBill bill = requireBill(payment.getBillId(), orgId);
        // Finance may have recorded a payment since this was raised.
        requireWithinDue(bill, lockPayable(bill, orgId), payment.getAmount());
        boolean self = authorizer.equals(payment.getRequestedBy());
        payment.setStatus(BillPaymentStatus.AUTHORIZED);
        payment.setAuthorizedBy(authorizer);
        payment.setAuthorizedAt(now());
        payment.setSelfAuthorized(self);
        payments.save(payment);
        record(bill, BillEventType.PAYMENT_AUTHORIZED, label(payment) + " of " + rupees(payment.getAmount()) + " authorized"
                + (self ? " by the system admin who raised it" : ""), authorizer);
    }

    /**
     * Declines a payment before any money moved. A payment a provider has acknowledged cannot be
     * rejected, since only the provider can end it now.
     */
    @Transactional
    public void reject(UUID paymentId, String reason, Long actor) {
        Long orgId = TenantContext.getCurrentOrgId();
        ContractBillPayment payment = lock(paymentId, orgId);
        boolean waiting = payment.getStatus() == BillPaymentStatus.REQUESTED;
        boolean unsent = payment.getStatus() == BillPaymentStatus.AUTHORIZED && payment.getProviderRef() == null;
        if (!waiting && !unsent) {
            throw new InvalidRequestException("This payment is " + statusText(payment) + " and can no longer be rejected");
        }
        payment.setStatus(BillPaymentStatus.REJECTED);
        payment.setFailureReason(reason.trim());
        payments.save(payment);
        record(requireBill(payment.getBillId(), orgId), BillEventType.PAYMENT_REJECTED,
                label(payment) + " of " + rupees(payment.getAmount()) + " rejected: " + reason.trim(), actor);
    }

    /** Says an authorized manual payment was made from the bank, which settles it. */
    @Transactional
    public void recordManual(UUID paymentId, RecordBillPaymentRequest req, Long actor) {
        Long orgId = TenantContext.getCurrentOrgId();
        ContractBillPayment payment = lock(paymentId, orgId);
        if (payment.getProvider() != BillPaymentProvider.MANUAL) {
            throw new InvalidRequestException("This payment is carried by " + payment.getProvider()
                    + ", which reports the outcome itself");
        }
        if (payment.getStatus() != BillPaymentStatus.AUTHORIZED) {
            throw new InvalidRequestException("This payment is " + statusText(payment)
                    + "; only an authorized payment can be recorded as paid");
        }
        if (req.paidOn().isAfter(LocalDate.now(clock))) {
            throw new InvalidRequestException("The payment date cannot be in the future");
        }
        payment.setBankReference(req.bankReference().trim());
        payment.setPaidOn(req.paidOn());
        settle(payment, orgId, actor, null);
    }

    // ---------------------------------------------------------------- what a provider says

    /** Records that the provider gave no answer, leaving the payment able to be sent again. */
    @Transactional
    public void noteProviderUnreachable(UUID paymentId, String message) {
        ContractBillPayment payment = lock(paymentId, TenantContext.getCurrentOrgId());
        if (payment.getStatus().isInFlight()) {
            payment.setLastError(message);
            payments.save(payment);
        }
    }

    /**
     * Applies a provider's answer. It only ever moves a payment forward, so a replayed or late answer
     * changes nothing. The one exception is a settlement of a payment rejected after a send whose answer
     * was lost: the money has gone, which is a fact the record has to show.
     */
    @Transactional
    public void applyOutcome(UUID paymentId, PayoutResult result) {
        Long orgId = TenantContext.getCurrentOrgId();
        ContractBillPayment payment = lock(paymentId, orgId);
        if (result.amountPaise() != null
                && result.amountPaise() != BillPaymentAmounts.toPaise(payment.getAmount())) {
            log.error("Bill payment {}: the provider reports {} paise, the payment is for {}; ignoring its answer",
                    paymentId, result.amountPaise(), payment.getAmount());
            return;
        }
        if (result.providerRef() != null && payment.getProviderRef() == null) {
            payment.setProviderRef(result.providerRef());
        }
        if (result.bankReference() != null) {
            payment.setBankReference(result.bankReference());
        }
        BillPaymentStatus status = payment.getStatus();
        switch (result.outcome()) {
            case PROCESSING -> {
                payment.setLastError(null);
                if (status == BillPaymentStatus.AUTHORIZED) {
                    payment.setStatus(BillPaymentStatus.PROCESSING);
                    record(requireBill(payment.getBillId(), orgId), BillEventType.PAYMENT_SENT,
                            label(payment) + " of " + rupees(payment.getAmount()) + " sent to the provider", null);
                }
                payments.save(payment);
            }
            case SETTLED -> {
                payment.setLastError(null);
                if (status.isInFlight() || status == BillPaymentStatus.REJECTED) {
                    payment.setPaidOn(LocalDate.now(clock));
                    settle(payment, orgId, null, status == BillPaymentStatus.REJECTED
                            ? "This payment had been rejected after a send with no answer, and the provider paid it. " : null);
                } else {
                    payments.save(payment);
                }
            }
            case FAILED -> {
                if (status.isInFlight()) {
                    fail(payment, orgId, result.failureReason());
                } else {
                    payments.save(payment);
                }
            }
            case REVERSED -> {
                if (status == BillPaymentStatus.SETTLED) {
                    reverse(payment, orgId, result.failureReason());
                } else if (status.isInFlight()) {
                    fail(payment, orgId, result.failureReason());
                } else {
                    payments.save(payment);
                }
            }
        }
    }

    private void settle(ContractBillPayment payment, Long orgId, Long actor, String prefix) {
        ContractBill bill = requireBill(payment.getBillId(), orgId);
        Payable payable = lockPayable(bill, orgId);
        BigDecimal due = payable.getAmountDue();
        // The money has gone either way, so a payable with less due than this settles for what it has
        // and the timeline says so. Refusing would lose the fact.
        BigDecimal applied = payment.getAmount().min(due.max(BigDecimal.ZERO));
        if (applied.signum() > 0) {
            payables.recordPayment(payable.getId(), applied);
        }
        payment.setAppliedAmount(applied);
        payment.setStatus(BillPaymentStatus.SETTLED);
        payment.setSettledAt(now());
        payments.save(payment);
        String note = (prefix == null ? "" : prefix) + label(payment) + " of " + rupees(payment.getAmount()) + " paid"
                + (payment.getBankReference() == null ? "" : ", reference " + payment.getBankReference());
        if (applied.compareTo(payment.getAmount()) < 0) {
            note += ". Only " + rupees(applied) + " was due on the payable by then, so finance has to reconcile the difference";
            log.warn("Bill payment {} settled for {} with {} due on payable {}", payment.getId(), payment.getAmount(),
                    due, payable.getId());
        }
        record(bill, BillEventType.PAYMENT_SETTLED, note, actor);
    }

    private void fail(ContractBillPayment payment, Long orgId, String reason) {
        String why = reason == null || reason.isBlank() ? "No reason given" : reason;
        payment.setStatus(BillPaymentStatus.FAILED);
        payment.setFailureReason(why);
        payment.setLastError(null);
        payments.save(payment);
        record(requireBill(payment.getBillId(), orgId), BillEventType.PAYMENT_FAILED,
                label(payment) + " of " + rupees(payment.getAmount()) + " failed: " + why, null);
    }

    private void reverse(ContractBillPayment payment, Long orgId, String reason) {
        ContractBill bill = requireBill(payment.getBillId(), orgId);
        lockPayable(bill, orgId);
        BigDecimal applied = payment.getAppliedAmount();
        if (applied != null && applied.signum() > 0) {
            payables.reversePayment(payment.getPayableId(), applied);
        }
        String why = reason == null || reason.isBlank() ? "No reason given" : reason;
        payment.setStatus(BillPaymentStatus.REVERSED);
        payment.setFailureReason(why);
        payments.save(payment);
        record(bill, BillEventType.PAYMENT_REVERSED, label(payment) + " of " + rupees(payment.getAmount())
                + " returned by the bank: " + why, null);
    }

    // ---------------------------------------------------------------- helpers

    private ContractBillPayment require(UUID id) {
        return payments.findScoped(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
    }

    private ContractBillPayment lock(UUID id, Long orgId) {
        return payments.lockScoped(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
    }

    private ContractBill requireBill(UUID id, Long orgId) {
        return bills.findScoped(id, orgId).orElseThrow(() -> new ResourceNotFoundException("Bill not found: " + id));
    }

    private Payable lockPayable(ContractBill bill, Long orgId) {
        return payableRepository.lockByIdAndOrganizationId(bill.getPayableId(), orgId)
                .orElseThrow(() -> new ResourceNotFoundException("The payable of bill " + bill.getBillNumber() + " was not found"));
    }

    private static void requireWithinDue(ContractBill bill, Payable payable, BigDecimal amount) {
        BigDecimal due = payable.getAmountDue();
        if (due.signum() <= 0) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is fully paid");
        }
        if (amount.compareTo(due) > 0) {
            throw new InvalidRequestException("Only " + rupees(due) + " is due on bill " + bill.getBillNumber());
        }
    }

    private static boolean hasBeneficiary(SubContract contract) {
        return notBlank(contract.getBankAccountNumber()) && notBlank(contract.getBankIfsc());
    }

    private static void requireBeneficiary(SubContract contract) {
        if (!hasBeneficiary(contract)) {
            throw new InvalidRequestException("Subcontract " + BillingSupport.contractRef(contract)
                    + " has no bank account number and IFSC; add them to the sub-contract before paying through the provider");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static BigDecimal paid(Payable payable) {
        return payable.getAmountPaid() == null ? BigDecimal.ZERO : payable.getAmountPaid();
    }

    private static BillPaymentState paymentState(ContractBill bill, Payable payable) {
        if (bill.getStatus() != BillStatus.APPROVED) {
            return null;
        }
        if (payable == null) {
            return BillPaymentState.NOTHING_PAYABLE;
        }
        if (paid(payable).signum() == 0) {
            return BillPaymentState.UNPAID;
        }
        return payable.getAmountDue().signum() > 0 ? BillPaymentState.PART_PAID : BillPaymentState.PAID;
    }

    private static String label(ContractBillPayment payment) {
        return payment.getProvider() == BillPaymentProvider.FAKE ? "Test payment" : "Payment";
    }

    private static String statusText(ContractBillPayment payment) {
        return payment.getStatus().name().toLowerCase();
    }

    private static String rupees(BigDecimal amount) {
        return "Rs. " + amount.toPlainString();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void record(ContractBill bill, BillEventType type, String note, Long actor) {
        ContractBillEvent event = new ContractBillEvent();
        event.setOrganization(bill.getOrganization());
        event.setBillId(bill.getId());
        event.setType(type);
        event.setNote(note);
        event.setActorUserId(actor);
        event.setActorName(actor == null ? PROVIDER_ACTOR : support.nameOf(actor, bill.getOrganization().getId()));
        events.save(event);
    }

    private BillPaymentDto toDto(ContractBillPayment p, Long orgId) {
        return new BillPaymentDto(p.getId(), p.getBillId(), p.getAmount(), p.getAppliedAmount(), p.getStatus(),
                p.getProvider(), p.getProvider() == BillPaymentProvider.FAKE, p.getBankReference(), p.getPaidOn(),
                p.getBeneficiaryName(), p.getBeneficiaryIfsc(), p.getBeneficiaryAccountLast4(), p.getNote(),
                p.getFailureReason(), p.getLastError(), support.nameOf(p.getRequestedBy(), orgId), p.getRequestedAt(),
                support.nameOf(p.getAuthorizedBy(), orgId), p.getAuthorizedAt(), p.isSelfAuthorized(), p.getSettledAt());
    }
}
