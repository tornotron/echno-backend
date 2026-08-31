package org.tornotron.echno_backend.finance.construction.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionPaymentMapper;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionPaymentMapperImpl;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionPaymentRepository;
import org.tornotron.echno_backend.user.UserDisplayName;
import org.tornotron.echno_backend.user.UserNameDirectory;
import org.tornotron.echno_backend.user.UserNameLookup;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins that a payment voucher comes back naming who verified it, and that a page costs one
 * directory read whatever its size.
 *
 * <p>The counterpart to {@code ConstructionInvoiceStampNameTest} and
 * {@code StockAdjustmentStampNameTest}: the same stamp, the same fix, and the same reason the
 * mapper cannot resolve it for itself. The real mapper is used so the test would catch a name that
 * came from a query inside the conversion rather than from the lookup the caller passed in.
 */
@ExtendWith(MockitoExtension.class)
class ConstructionPaymentStampNameTest {

    private static final long ORG_ID = 9L;
    private static final long VERIFIER = 41L;
    private static final long NAMELESS_VERIFIER = 42L;
    private static final long DELETED_USER = 43L;

    @Mock private ConstructionPaymentRepository paymentRepo;
    @Mock private EntryNumberGenerator numberGen;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private UserNameDirectory userNameDirectory;

    private final ConstructionPaymentMapper mapper = new ConstructionPaymentMapperImpl();

    private ConstructionPaymentService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new ConstructionPaymentService(paymentRepo, numberGen, mapper, tenantEntityHelper,
                userNameDirectory);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void readingOneVoucher_namesTheVerifier() {
        UUID id = UUID.randomUUID();
        when(paymentRepo.findByIdScoped(id)).thenReturn(Optional.of(payment(id, VERIFIER)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(VERIFIER, "Aneesh Johny", "aneesh@echno.test"))));

        ConstructionPaymentDto dto = service.findById(id);

        assertThat(dto.verifiedBy()).isEqualTo(VERIFIER);
        assertThat(dto.verifiedByName()).isEqualTo("Aneesh Johny");
    }

    @Test
    void anUnverifiedVoucherHasNoVerifierNameRatherThanAPlaceholder() {
        UUID id = UUID.randomUUID();
        when(paymentRepo.findByIdScoped(id)).thenReturn(Optional.of(payment(id, null)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.none());

        ConstructionPaymentDto dto = service.findById(id);

        assertThat(dto.verifiedByName()).isNull();
    }

    @Test
    void aVerifierWhoseAccountIsGone_stillRendersOnTheVoucher() {
        UUID id = UUID.randomUUID();
        when(paymentRepo.findByIdScoped(id)).thenReturn(Optional.of(payment(id, DELETED_USER)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.none());

        ConstructionPaymentDto dto = service.findById(id);

        assertThat(dto.verifiedByName()).isEqualTo("User #" + DELETED_USER);
    }

    @Test
    void aVerifierWithNoNameFallsBackToTheirEmail() {
        UUID id = UUID.randomUUID();
        when(paymentRepo.findByIdScoped(id))
                .thenReturn(Optional.of(payment(id, NAMELESS_VERIFIER)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(NAMELESS_VERIFIER, "  ", "hrishi@echno.test"))));

        ConstructionPaymentDto dto = service.findById(id);

        assertThat(dto.verifiedByName()).isEqualTo("hrishi@echno.test");
    }

    @Test
    void listingAPage_readsTheDirectoryOnceForEveryStampOnIt() {
        List<ConstructionPayment> page = List.of(
                payment(UUID.randomUUID(), VERIFIER),
                payment(UUID.randomUUID(), null),
                payment(UUID.randomUUID(), DELETED_USER));
        when(paymentRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(page));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(VERIFIER, "Aneesh Johny", "aneesh@echno.test"))));

        List<ConstructionPaymentDto> dtos = service
                .findAll(null, null, null, null, null, Pageable.unpaged()).getContent();

        ArgumentCaptor<Collection<Long>> asked = ArgumentCaptor.captor();
        verify(userNameDirectory, times(1)).namesFor(asked.capture());
        assertThat(asked.getValue()).contains(VERIFIER, DELETED_USER);
        assertThat(dtos).extracting(ConstructionPaymentDto::verifiedByName)
                .containsExactly("Aneesh Johny", null, "User #" + DELETED_USER);
    }

    private ConstructionPayment payment(UUID id, Long verifiedBy) {
        ConstructionPayment payment = new ConstructionPayment();
        payment.setId(id);
        payment.setVerifiedBy(verifiedBy);
        return payment;
    }
}
