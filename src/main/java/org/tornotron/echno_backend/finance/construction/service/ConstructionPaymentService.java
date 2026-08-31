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
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionPaymentMapper;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionPaymentRepository;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionPaymentSpecifications;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserNameDirectory;
import org.tornotron.echno_backend.user.UserNameLookup;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * CRUD + list + verification for construction payment vouchers. This increment deliberately
 * does NO ledger or journal posting: the status is set directly and no JournalEntry is
 * created. A later increment will add the ledger-posting hooks (post the cash/bank
 * and payable movement on completion, reverse on cancel/refund).
 *
 * <p>Who raised a voucher and who verified it are both read from the session, never from a
 * payload. {@link #verify} is the only writer of the verification stamp, and {@link #update}
 * cannot reach it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConstructionPaymentService {

    private static final String DOC_TYPE = "CPMT";
    private static final String DEFAULT_CURRENCY = "INR";

    private final ConstructionPaymentRepository paymentRepo;
    private final EntryNumberGenerator numberGen;
    private final ConstructionPaymentMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserNameDirectory userNameDirectory;
    private final UserContextService userContextService;
    private final SelfApprovalPolicy selfApprovalPolicy;

    @Transactional(readOnly = true)
    public ConstructionPaymentDto findById(UUID id) {
        return toDto(paymentRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction payment with ID " + id + " was not found")));
    }

    @Transactional(readOnly = true)
    public Page<ConstructionPaymentDto> findAll(Long projectId,
                                                Long vendorId,
                                                ConstructionPaymentVoucherStatus status,
                                                ConstructionPaymentType type,
                                                ConstructionPayeeType payeeType,
                                                Pageable pageable) {
        Page<ConstructionPayment> payments = paymentRepo.findAll(
                ConstructionPaymentSpecifications.withFilters(projectId, vendorId, status, type, payeeType),
                pageable);
        UserNameLookup names = namesFor(payments.getContent());
        return payments.map(payment -> mapper.toDto(payment, names));
    }

    @Transactional
    public ConstructionPaymentDto create(CreateConstructionPaymentRequest req) {
        ConstructionPayment payment = new ConstructionPayment();
        payment.setPaymentNumber(numberGen.next(DOC_TYPE));
        payment.setType(req.type());
        payment.setStatus(ConstructionPaymentVoucherStatus.PENDING);
        payment.setMethod(req.method());
        payment.setPayeeType(req.payeeType());
        payment.setProjectId(req.projectId());
        payment.setInvoiceId(req.invoiceId());
        payment.setPurchaseOrderId(req.purchaseOrderId());
        payment.setVendorId(req.vendorId());
        payment.setEmployeeId(req.employeeId());
        payment.setSubContractId(req.subContractId());
        payment.setLabourId(req.labourId());
        payment.setPayeeName(req.payeeName());
        payment.setPayeeDetails(req.payeeDetails());
        payment.setAmount(MoneyUtils.normalize(req.amount()));
        payment.setCurrency(resolveCurrency(req.currency()));
        payment.setPaymentDate(req.paymentDate());
        payment.setTransactionId(req.transactionId());
        payment.setReferenceNumber(req.referenceNumber());
        payment.setBankName(req.bankName());
        payment.setAccountNumber(req.accountNumber());
        payment.setIfscCode(req.ifscCode());
        payment.setRaisedBy(userContextService.getCurrentUserId());
        payment.setDescription(req.description());
        payment.setNotes(req.notes());
        payment.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        ConstructionPayment saved = paymentRepo.save(payment);
        log.info("Created construction payment {}", saved.getPaymentNumber());
        return toDto(saved);
    }

    @Transactional
    public ConstructionPaymentDto update(UUID id, UpdateConstructionPaymentRequest req) {
        ConstructionPayment payment = paymentRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction payment with ID " + id + " was not found"));

        if (payment.getStatus() == ConstructionPaymentVoucherStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Construction payment " + payment.getPaymentNumber()
                            + " is cancelled and cannot be updated");
        }

        payment.setType(req.type());
        payment.setStatus(req.status());
        payment.setMethod(req.method());
        payment.setPayeeType(req.payeeType());
        payment.setProjectId(req.projectId());
        payment.setInvoiceId(req.invoiceId());
        payment.setPurchaseOrderId(req.purchaseOrderId());
        payment.setVendorId(req.vendorId());
        payment.setEmployeeId(req.employeeId());
        payment.setSubContractId(req.subContractId());
        payment.setLabourId(req.labourId());
        payment.setPayeeName(req.payeeName());
        payment.setPayeeDetails(req.payeeDetails());
        payment.setAmount(MoneyUtils.normalize(req.amount()));
        payment.setCurrency(resolveCurrency(req.currency()));
        payment.setPaymentDate(req.paymentDate());
        payment.setTransactionId(req.transactionId());
        payment.setReferenceNumber(req.referenceNumber());
        payment.setBankName(req.bankName());
        payment.setAccountNumber(req.accountNumber());
        payment.setIfscCode(req.ifscCode());
        payment.setDescription(req.description());
        payment.setNotes(req.notes());

        log.info("Updated construction payment {}", payment.getPaymentNumber());
        return toDto(payment);
    }

    /**
     * Records that the voucher has been verified, stamping the verifier from the session and the
     * time from the clock.
     *
     * <p>This is an action rather than a pair of editable fields for the reason every attribution
     * stamp on this codebase became session-derived: a stamp a caller can write is a statement
     * about someone else that nobody made. On a payment voucher it is wrong twice over, because
     * the record would name a person who never checked the payment and would say so silently.
     * {@link #update} therefore cannot reach these two columns at all.
     *
     * <p>Verification is refused where there is nothing left to verify: a cancelled voucher, and a
     * voucher already verified. Re-verifying is not an idempotent no-op, it would overwrite a
     * stamp somebody else's check produced, so it is refused and the existing stamp stands. There
     * is deliberately no action to clear a verification: an unverify would be the same silent
     * rewrite of the audit trail from the other direction, and nothing has asked for one.
     *
     * <p>Whoever raised the voucher cannot verify it, on the rule every other second-pair-of-eyes
     * check here follows: see {@link SelfApprovalPolicy}. A voucher raised before the raiser was
     * stamped carries no id to compare, and the policy allows that verification through at WARN
     * rather than stranding it.
     *
     * @param id The voucher to verify.
     * @return The verified voucher, naming the verifier.
     */
    @Transactional
    public ConstructionPaymentDto verify(UUID id) {
        ConstructionPayment payment = paymentRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction payment with ID " + id + " was not found"));

        if (payment.getStatus() == ConstructionPaymentVoucherStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Construction payment " + payment.getPaymentNumber()
                            + " is cancelled and cannot be verified");
        }
        if (payment.getVerifiedAt() != null) {
            throw new InvalidRequestException(
                    "Construction payment " + payment.getPaymentNumber()
                            + " has already been verified, and a verification cannot be replaced");
        }

        Long verifier = userContextService.getCurrentUserId();
        selfApprovalPolicy.checkSelfApproval(
                ApprovalParty.ofUser(payment.getRaisedBy()),
                ApprovalParty.ofUser(verifier),
                "Construction payment " + payment.getPaymentNumber());

        payment.setVerifiedBy(verifier);
        payment.setVerifiedAt(Instant.now());

        log.info("Verified construction payment {}", payment.getPaymentNumber());
        return toDto(payment);
    }

    private String resolveCurrency(String currency) {
        return (currency == null || currency.isBlank()) ? DEFAULT_CURRENCY : currency;
    }

    /**
     * Converts one voucher, resolving its verifier name first.
     *
     * @param payment The payment to convert.
     * @return The payment DTO, with the verifier named.
     */
    private ConstructionPaymentDto toDto(ConstructionPayment payment) {
        return mapper.toDto(payment, namesFor(List.of(payment)));
    }

    /**
     * Reads the display name for every raiser and verifier stamp on the vouchers about to be mapped.
     *
     * <p>One call covers a whole page and both stamps, so the query count follows neither the row
     * count nor the number of stamps per row. The mapper cannot do this for itself: the voucher
     * holds only the user ids, and a lookup inside the conversion would cost a round trip per row
     * with nothing at the call site to show it, which is what {@code MapperDatabaseAccessTest}
     * exists to prevent.
     *
     * @param payments The payments being converted.
     * @return Their raiser and verifier names, with an id that no longer resolves reading as a
     *         placeholder.
     */
    private UserNameLookup namesFor(Collection<ConstructionPayment> payments) {
        return userNameDirectory.namesFor(payments.stream()
                .flatMap(payment -> Stream.of(payment.getRaisedBy(), payment.getVerifiedBy()))
                .toList());
    }
}
