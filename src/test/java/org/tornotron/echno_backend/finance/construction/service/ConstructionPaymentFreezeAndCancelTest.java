package org.tornotron.echno_backend.finance.construction.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentMethod;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionPaymentMapper;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionPaymentMapperImpl;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionPaymentRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserNameDirectory;
import org.tornotron.echno_backend.user.UserNameLookup;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A verified voucher is frozen, and cancelling is how it gets corrected.
 *
 * <p>#631 made the verification stamp unwritable from a payload, which stopped it being forged. It
 * did not stop it being outlived: the amount, the payee, the bank details and the payment date
 * could all still be replaced underneath a stamp that stayed where it was, so a voucher raised for
 * 1,000 and checked by a colleague could become one for 100,000 with the verification still
 * reading as current. Freezing the document is the half that makes the stamp mean something.
 *
 * <p>Three shapes were available and the choice matters, so it is recorded here rather than only
 * in the pull request. **Clearing the stamp on an edit** was rejected: it never strands anyone, but
 * it turns every edit into an unverify, which is the silent rewrite of the record #631 declined to
 * build as an action and would be no better arriving by the back door. **Freezing only the fields
 * the verification is about** was rejected because the boundary is a judgement that drifts: every
 * field added later has to be classified, and misclassifying one reopens the hole with nothing to
 * catch it. **Refusing the edit outright** is what is implemented, on the rule
 * {@code StockAdjustmentService} already applies to a posted document. Its failure mode is being
 * stuck, and that is answered by {@code cancel} rather than by leaving the document editable.
 */
@ExtendWith(MockitoExtension.class)
class ConstructionPaymentFreezeAndCancelTest {

    private static final long ORG_ID = 9L;
    private static final long RAISER = 51L;
    private static final long VERIFIER = 52L;
    private static final BigDecimal CHECKED_AMOUNT = new BigDecimal("1000.00");

    @Mock private ConstructionPaymentRepository paymentRepo;
    @Mock private EntryNumberGenerator numberGen;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private UserNameDirectory userNameDirectory;
    @Mock private UserContextService userContextService;
    @Mock private OrganizationSecurityService orgSecurity;

    private final ConstructionPaymentMapper mapper = new ConstructionPaymentMapperImpl();

    private ConstructionPaymentService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new ConstructionPaymentService(paymentRepo, numberGen, mapper, tenantEntityHelper,
                userNameDirectory, userContextService, new SelfApprovalPolicy(orgSecurity));
        lenient().when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.none());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── The freeze ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a verified voucher cannot have its amount replaced")
    void aVerifiedVoucherCannotBeEdited() {
        ConstructionPayment payment = verified();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.update(payment.getId(), anEditRaisingTheAmount()))
                .withMessageContaining("cannot be edited");

        assertThat(payment.getAmount()).isEqualByComparingTo(CHECKED_AMOUNT);
        assertThat(payment.getPayeeName()).isEqualTo("Sundar Building Materials");
    }

    @Test
    @DisplayName("the refusal names the correction route, rather than leaving the voucher stuck")
    void theRefusalNamesTheCorrectionRoute() {
        ConstructionPayment payment = verified();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.update(payment.getId(), anEditRaisingTheAmount()))
                .withMessageContaining("Cancel it and raise a replacement");
    }

    @Test
    @DisplayName("a voucher nobody has checked is still fully editable")
    void anUnverifiedVoucherIsStillEditable() {
        ConstructionPayment payment = pending();

        service.update(payment.getId(), anEditRaisingTheAmount());

        // MoneyUtils rescales on the way in, so compare the value rather than the scale.
        assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    // ── Cancelling ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelling records the reason and leaves the verification standing")
    void cancellingRecordsTheReasonAndKeepsTheStamp() {
        ConstructionPayment payment = verified();
        Instant stampedAt = payment.getVerifiedAt();

        ConstructionPaymentDto cancelled = service.cancel(
                payment.getId(), "Duplicate of CPMT-000118, raised twice for the same invoice");

        assertThat(payment.getStatus()).isEqualTo(ConstructionPaymentVoucherStatus.CANCELLED);
        assertThat(cancelled.cancellationReason())
                .isEqualTo("Duplicate of CPMT-000118, raised twice for the same invoice");
        // The stamp stays: the record has to keep showing who checked the voucher that was voided.
        assertThat(payment.getVerifiedBy()).isEqualTo(VERIFIER);
        assertThat(payment.getVerifiedAt()).isEqualTo(stampedAt);
        assertThat(payment.getAmount()).isEqualByComparingTo(CHECKED_AMOUNT);
    }

    @Test
    @DisplayName("a cancelled voucher's reason is not replaced by a second cancellation")
    void aSecondCancellationIsRefused() {
        ConstructionPayment payment = verified();
        service.cancel(payment.getId(), "Duplicate of CPMT-000118");

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.cancel(payment.getId(), "Changed my mind"))
                .withMessageContaining("already cancelled");

        assertThat(payment.getCancellationReason()).isEqualTo("Duplicate of CPMT-000118");
    }

    @Test
    @DisplayName("an update cannot cancel a voucher, so no cancellation escapes recording a reason")
    void anUpdateCannotCancel() {
        ConstructionPayment payment = pending();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.update(payment.getId(), anEditThatCancels()))
                .withMessageContaining("Use the cancel action");

        assertThat(payment.getStatus()).isEqualTo(ConstructionPaymentVoucherStatus.PENDING);
        assertThat(payment.getCancellationReason()).isNull();
    }

    // ── The read that all three decisions hang off ───────────────────────────

    @Test
    @DisplayName("every write path reads the voucher under a write lock")
    void everyWritePathReadsUnderAWriteLock() {
        ConstructionPayment payment = pending();

        service.update(payment.getId(), anEditRaisingTheAmount());
        service.cancel(payment.getId(), "Duplicate");

        // Each of update, verify and cancel reads the voucher's state, decides on it and then
        // writes. Two arriving together would each read it as it was before the other, both pass,
        // and both act. This pins that they read through the locking finder; it cannot demonstrate
        // the database behaviour, which the lock annotation is responsible for.
        verify(paymentRepo, never()).findByIdScoped(payment.getId());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** A pending voucher raised by {@link #RAISER}, registered with the repository stub. */
    private ConstructionPayment pending() {
        UUID id = UUID.randomUUID();
        ConstructionPayment payment = new ConstructionPayment();
        payment.setId(id);
        payment.setPaymentNumber("CPMT-2026-0031");
        payment.setStatus(ConstructionPaymentVoucherStatus.PENDING);
        payment.setAmount(CHECKED_AMOUNT);
        payment.setPayeeName("Sundar Building Materials");
        payment.setRaisedBy(RAISER);
        when(paymentRepo.lockByIdScoped(id)).thenReturn(Optional.of(payment));
        return payment;
    }

    /** The same voucher after somebody checked it, verified through the action rather than stubbed. */
    private ConstructionPayment verified() {
        ConstructionPayment payment = pending();
        when(userContextService.getCurrentUserId()).thenReturn(VERIFIER);
        service.verify(payment.getId());
        return payment;
    }

    /** An edit that moves the figures the verification was about. */
    private UpdateConstructionPaymentRequest anEditRaisingTheAmount() {
        return anEdit(ConstructionPaymentVoucherStatus.COMPLETED, new BigDecimal("100000.00"));
    }

    /** An edit whose only intent is to void the voucher, which is what the cancel action is for. */
    private UpdateConstructionPaymentRequest anEditThatCancels() {
        return anEdit(ConstructionPaymentVoucherStatus.CANCELLED, CHECKED_AMOUNT);
    }

    private UpdateConstructionPaymentRequest anEdit(ConstructionPaymentVoucherStatus status,
                                                     BigDecimal amount) {
        return new UpdateConstructionPaymentRequest(
                ConstructionPaymentType.INVOICE,
                status,
                ConstructionPaymentMethod.UPI,
                ConstructionPayeeType.VENDOR,
                42L,
                null, null, 17L, null, null, null,
                "Someone Else Entirely", null,
                amount,
                "INR",
                LocalDate.of(2026, 8, 2),
                null, null, null, null, null,
                "Revised running payment", "Settled");
    }
}
