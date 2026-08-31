package org.tornotron.echno_backend.finance.construction.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.when;

/**
 * The verify action: who a verification records, and when it is refused.
 *
 * <p>Verification of a payment voucher is a transition, not an attribute, which is why it is an
 * action rather than a pair of fields on the update payload. The stamp it writes names the session
 * and nothing else, so a caller cannot record a colleague as having checked a payment they never
 * saw, and cannot backdate the check either.
 *
 * <p>The self-approval rule is the real policy rather than a mock, because what is being pinned is
 * the outcome of the comparison, not that a call was made. Segregation of duties applies here for
 * the reason it applies to an approval: the verification is the second pair of eyes on money that
 * has gone out, and the role gate does not supply it, since whoever may verify may also raise.
 */
@ExtendWith(MockitoExtension.class)
class ConstructionPaymentVerifyActionTest {

    private static final long ORG_ID = 9L;
    private static final long RAISER = 51L;
    private static final long VERIFIER = 52L;

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

    @Test
    void verifying_stampsTheSessionUserAndTheClock() {
        ConstructionPayment payment = pending();
        Instant before = Instant.now();
        when(userContextService.getCurrentUserId()).thenReturn(VERIFIER);

        service.verify(payment.getId());

        assertThat(payment.getVerifiedBy()).isEqualTo(VERIFIER);
        assertThat(payment.getVerifiedAt()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void anUpdateCannotWriteTheStamp_soAVerifiedVoucherSurvivesAnEdit() {
        ConstructionPayment payment = pending();
        when(userContextService.getCurrentUserId()).thenReturn(VERIFIER);
        service.verify(payment.getId());

        Long stampedBy = payment.getVerifiedBy();
        Instant stampedAt = payment.getVerifiedAt();

        service.update(payment.getId(), anEdit());

        assertThat(payment.getVerifiedBy()).isEqualTo(stampedBy);
        assertThat(payment.getVerifiedAt()).isEqualTo(stampedAt);
    }

    @Test
    void theRaiserCannotVerifyTheirOwnVoucher() {
        ConstructionPayment payment = pending();
        when(userContextService.getCurrentUserId()).thenReturn(RAISER);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.verify(payment.getId()))
                .withMessageContaining("raised by the same person");

        assertThat(payment.getVerifiedBy()).isNull();
        assertThat(payment.getVerifiedAt()).isNull();
    }

    @Test
    void aSystemAdministratorMayVerifyTheirOwnVoucher_asTheRecordedException() {
        ConstructionPayment payment = pending();
        when(userContextService.getCurrentUserId()).thenReturn(RAISER);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(true);

        service.verify(payment.getId());

        assertThat(payment.getVerifiedBy()).isEqualTo(RAISER);
    }

    @Test
    void aSessionThatResolvesToNoUser_isRefusedRatherThanStampingNobody() {
        ConstructionPayment payment = pending();
        when(userContextService.getCurrentUserId()).thenReturn(null);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.verify(payment.getId()))
                .withMessageContaining("resolves to no user");

        assertThat(payment.getVerifiedBy()).isNull();
    }

    @Test
    void aVoucherRaisedBeforeTheRaiserWasRecorded_isStillVerifiable() {
        ConstructionPayment payment = pending();
        payment.setRaisedBy(null);
        when(userContextService.getCurrentUserId()).thenReturn(VERIFIER);

        service.verify(payment.getId());

        assertThat(payment.getVerifiedBy()).isEqualTo(VERIFIER);
    }

    @Test
    void aVerificationAlreadyRecorded_isNotReplaced() {
        ConstructionPayment payment = pending();
        Instant original = Instant.parse("2026-08-06T10:20:00Z");
        payment.setVerifiedBy(VERIFIER);
        payment.setVerifiedAt(original);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.verify(payment.getId()))
                .withMessageContaining("already been verified");

        assertThat(payment.getVerifiedBy()).isEqualTo(VERIFIER);
        assertThat(payment.getVerifiedAt()).isEqualTo(original);
    }

    @Test
    void aCancelledVoucherCannotBeVerified() {
        ConstructionPayment payment = pending();
        payment.setStatus(ConstructionPaymentVoucherStatus.CANCELLED);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.verify(payment.getId()))
                .withMessageContaining("cancelled");

        assertThat(payment.getVerifiedAt()).isNull();
    }

    /** An ordinary edit of the voucher: every field the update payload still carries. */
    private UpdateConstructionPaymentRequest anEdit() {
        return new UpdateConstructionPaymentRequest(
                ConstructionPaymentType.INVOICE,
                ConstructionPaymentVoucherStatus.COMPLETED,
                ConstructionPaymentMethod.UPI,
                ConstructionPayeeType.VENDOR,
                42L,
                null, null, 17L, null, null, null,
                "Acme Supplies", null,
                new BigDecimal("16000"),
                "INR",
                LocalDate.of(2026, 8, 2),
                null, null, null, null, null,
                "Revised running payment", "Settled");
    }

    /** A pending voucher raised by {@link #RAISER}, already registered with the repository stub. */
    private ConstructionPayment pending() {
        UUID id = UUID.randomUUID();
        ConstructionPayment payment = new ConstructionPayment();
        payment.setId(id);
        payment.setPaymentNumber("CPMT-2026-0031");
        payment.setStatus(ConstructionPaymentVoucherStatus.PENDING);
        payment.setRaisedBy(RAISER);
        when(paymentRepo.findByIdScoped(id)).thenReturn(Optional.of(payment));
        return payment;
    }
}
