package org.tornotron.echno_backend.finance.construction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import org.tornotron.echno_backend.user.UserNameDirectory;
import org.tornotron.echno_backend.user.UserNameLookup;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * CRUD + list for construction payment vouchers. This increment deliberately does
 * NO ledger or journal posting: the status is set directly and no JournalEntry is
 * created. A later increment will add the ledger-posting hooks (post the cash/bank
 * and payable movement on completion, reverse on cancel/refund).
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
        payment.setVerifiedBy(req.verifiedBy());
        payment.setVerifiedAt(req.verifiedAt());
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
        payment.setVerifiedBy(req.verifiedBy());
        payment.setVerifiedAt(req.verifiedAt());
        payment.setDescription(req.description());
        payment.setNotes(req.notes());

        log.info("Updated construction payment {}", payment.getPaymentNumber());
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
     * Reads the display name for every verifier stamp on the vouchers about to be mapped.
     *
     * <p>One call covers a whole page, so the query count does not follow the row count. The
     * mapper cannot do this for itself: the voucher holds only the user id, and a lookup inside
     * the conversion would cost a round trip per row with nothing at the call site to show it,
     * which is what {@code MapperDatabaseAccessTest} exists to prevent.
     *
     * @param payments The payments being converted.
     * @return Their verifier names, with an id that no longer resolves reading as a placeholder.
     */
    private UserNameLookup namesFor(Collection<ConstructionPayment> payments) {
        return userNameDirectory.namesFor(payments.stream()
                .map(ConstructionPayment::getVerifiedBy)
                .toList());
    }
}
