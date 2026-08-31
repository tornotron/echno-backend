package org.tornotron.echno_backend.finance.construction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    /**
     * One page of the voucher register, narrowed by any of the filters supplied.
     *
     * <p>Every dimension the payments screen narrows by is a query predicate here, including the
     * three person filters. They were added under issue #638, where the web list was filtering a
     * page of twenty by verifier in the browser and presenting the result as the register: a
     * filter over one page answers a different question from the chip above it, and says nothing
     * about the rows it never saw.
     *
     * <p>The page bounds arrive as a {@code PageQuery} on the endpoint rather than a Spring
     * {@code Pageable}. A {@code Pageable} took its default size from
     * {@code spring.data.web.pageable.default-page-size}, which was never set, so Spring's own
     * default of twenty applied and no caller could tell where the number came from.
     *
     * @param projectId  Project the voucher is charged to, or null for every project.
     * @param vendorId   Vendor being paid, or null for every vendor.
     * @param status     Lifecycle status, or null for every status.
     * @param type       Kind of payment, or null for every kind.
     * @param payeeType  Category of party paid, or null for every category.
     * @param employeeId Employee being paid. An employee id, or null for every payee.
     * @param verifiedBy User id that verified the voucher, or null for any verifier.
     * @param raisedBy   User id that raised the voucher, or null for any raiser.
     * @param pageNo     Zero-based page index.
     * @param pageSize   Rows per page.
     * @return That page of matching vouchers.
     */
    @Transactional(readOnly = true)
    public Page<ConstructionPaymentDto> findAll(Long projectId,
                                                Long vendorId,
                                                ConstructionPaymentVoucherStatus status,
                                                ConstructionPaymentType type,
                                                ConstructionPayeeType payeeType,
                                                Long employeeId,
                                                Long verifiedBy,
                                                Long raisedBy,
                                                int pageNo,
                                                int pageSize) {
        Page<ConstructionPayment> payments = paymentRepo.findAll(
                ConstructionPaymentSpecifications.withFilters(
                        projectId, vendorId, status, type, payeeType, employeeId, verifiedBy, raisedBy),
                PageRequest.of(pageNo, pageSize, ConstructionPaymentSpecifications.LIST_ORDER));
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

    /**
     * Replaces the editable fields of a voucher that has not been checked yet.
     *
     * <p><b>A verified voucher is frozen.</b> The verification says somebody looked at these
     * figures; leaving them editable afterwards meant the stamp went on attesting to whatever
     * replaced them, so a voucher raised for 1,000 and verified by a colleague could be edited to
     * 100,000 with the verification still reading as current. The stamp itself has been
     * unwritable from a payload since #631, which stopped it being forged but not being
     * outlived. Freezing the document is the half that makes it mean something, and it is the
     * rule {@code StockAdjustmentService} already applies once a document is posted.
     *
     * <p>The correction route is {@link #cancel} and raise a fresh voucher, not an edit and not an
     * unverify. An unverify would be the same silent rewrite of the record from the other
     * direction, which #631 declined for the same reason. A cancellation leaves the wrong voucher
     * standing with its verification, its reason for being voided, and the replacement raised
     * beside it, which is what makes the correction explainable afterwards.
     *
     * <p>Cancelling is not done here either, even on an unverified voucher. It is a transition
     * with meaning rather than a field to set, it has to record why, and routing it through a full
     * replacement of every other field is how a cancellation ends up also changing an amount.
     *
     * @param id The voucher to replace.
     * @param req The replacement fields.
     * @return The updated voucher.
     * @throws ResourceNotFoundException if no such voucher exists in this organization.
     * @throws InvalidRequestException if the voucher is verified or cancelled, or if the payload
     *         asks for the cancelled status.
     */
    @Transactional
    public ConstructionPaymentDto update(UUID id, UpdateConstructionPaymentRequest req) {
        ConstructionPayment payment = paymentRepo.lockByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction payment with ID " + id + " was not found"));

        if (payment.getStatus() == ConstructionPaymentVoucherStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Construction payment " + payment.getPaymentNumber()
                            + " is cancelled and cannot be updated");
        }
        if (payment.getVerifiedAt() != null) {
            throw new InvalidRequestException(
                    "Construction payment " + payment.getPaymentNumber() + " was verified on "
                            + payment.getVerifiedAt() + " and cannot be edited, because the "
                            + "verification would then stand against figures nobody checked. "
                            + "Cancel it and raise a replacement.");
        }
        if (req.status() == ConstructionPaymentVoucherStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Construction payment " + payment.getPaymentNumber() + " cannot be cancelled "
                            + "through an update. Use the cancel action, which records why the "
                            + "voucher was voided.");
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
     * <p>This is an action rather than a pair of editable fields for the reason the attribution
     * stamps in this module are session-derived: a stamp a caller can write is a statement about
     * someone else that nobody made. On a payment voucher it is wrong twice over, because the
     * record would name a person who never checked the payment and would say so silently.
     * {@link #update} therefore cannot reach these two columns at all. The one instance of the
     * same shape still outstanding is
     * {@code MovementRecordService.verifyMovement}, which takes its verifier from a request
     * parameter.
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
        ConstructionPayment payment = paymentRepo.lockByIdScoped(id)
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

    /**
     * Voids a voucher, recording why.
     *
     * <p>This is the only route to the cancelled status, and it is the correction route for a
     * voucher that has been verified, since {@link #update} freezes one. A verified voucher can be
     * cancelled: voiding a document is not editing the figures its verification attested to, it
     * withdraws the document those figures were on, and the stamp stays where it is so the record
     * still shows who checked what and that it was later thrown out.
     *
     * <p>The reason is required, for the reason {@code StockAdjustmentService.reject} requires
     * one: a voided document whose purpose was to explain a payment explains nothing if the
     * voiding does not say what was wrong with it, and on a verified voucher it is also the only
     * record of why somebody's check was set aside.
     *
     * <p>Cancelling is deliberately one-way. There is no action to bring a voucher back, for the
     * same reason there is no unverify: the replacement is raised alongside it.
     *
     * @param id The voucher to void.
     * @param reason Why it is being voided.
     * @return The cancelled voucher.
     * @throws ResourceNotFoundException if no such voucher exists in this organization.
     * @throws InvalidRequestException if the voucher is already cancelled.
     */
    @Transactional
    public ConstructionPaymentDto cancel(UUID id, String reason) {
        ConstructionPayment payment = paymentRepo.lockByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction payment with ID " + id + " was not found"));

        if (payment.getStatus() == ConstructionPaymentVoucherStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Construction payment " + payment.getPaymentNumber()
                            + " is already cancelled, and the reason it was cancelled for is not replaced");
        }

        payment.setStatus(ConstructionPaymentVoucherStatus.CANCELLED);
        payment.setCancellationReason(reason);

        log.info("Cancelled construction payment {}", payment.getPaymentNumber());
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
