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
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapper;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapperImpl;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceRepository;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsService;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;
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
 * Pins that a construction invoice comes back naming who submitted, approved and paid it, and
 * that a page costs one directory read whatever its size.
 *
 * <p>The counterpart to {@code StockAdjustmentStampNameTest}: the same stamps, the same fix, and
 * the same reason the mapper cannot resolve them for itself. The real mapper is used so the test
 * would catch a name that came from a query inside the conversion rather than from the lookup the
 * caller passed in.
 */
@ExtendWith(MockitoExtension.class)
class ConstructionInvoiceStampNameTest {

    private static final long ORG_ID = 9L;
    private static final long SUBMITTER = 31L;
    private static final long APPROVER = 32L;
    private static final long DELETED_USER = 33L;

    @Mock private ConstructionInvoiceRepository invoiceRepo;
    @Mock private EntryNumberGenerator numberGen;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private JournalEntryRepository journalRepo;
    @Mock private JournalPostingService postingService;
    @Mock private PostingAccountResolver postingAccountResolver;
    @Mock private FinanceSettingsService financeSettingsService;
    @Mock private ProjectRepository projectRepository;
    @Mock private UserContextService userContextService;
    @Mock private CostCategoryRepository costCategoryRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private InvoiceService invoiceService;
    @Mock private OrganizationSecurityService orgSecurity;
    @Mock private UserNameDirectory userNameDirectory;

    private final ConstructionInvoiceMapper mapper = new ConstructionInvoiceMapperImpl();

    private ConstructionInvoiceService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new ConstructionInvoiceService(invoiceRepo, numberGen, mapper, tenantEntityHelper,
                journalRepo, postingService, postingAccountResolver, financeSettingsService,
                projectRepository, userContextService, costCategoryRepository, customerRepository,
                invoiceService, new SelfApprovalPolicy(orgSecurity), userNameDirectory);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void readingOneInvoice_namesTheSubmitterTheApproverAndThePaymentRecorder() {
        UUID id = UUID.randomUUID();
        when(invoiceRepo.findByIdWithLines(id))
                .thenReturn(Optional.of(invoice(id, SUBMITTER, APPROVER, SUBMITTER)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(SUBMITTER, "Anand Rajashekar", "anand@echno.test"),
                new UserDisplayName(APPROVER, "Aneesh Johny", "aneesh@echno.test"))));

        ConstructionInvoiceDto dto = service.findById(id);

        assertThat(dto.submittedByName()).isEqualTo("Anand Rajashekar");
        assertThat(dto.approvedByName()).isEqualTo("Aneesh Johny");
        assertThat(dto.paymentRecordedByName()).isEqualTo("Anand Rajashekar");
    }

    @Test
    void anUnpaidInvoiceHasNoPaymentRecorderRatherThanAPlaceholder() {
        UUID id = UUID.randomUUID();
        when(invoiceRepo.findByIdWithLines(id))
                .thenReturn(Optional.of(invoice(id, SUBMITTER, null, null)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(SUBMITTER, "Anand Rajashekar", "anand@echno.test"))));

        ConstructionInvoiceDto dto = service.findById(id);

        assertThat(dto.approvedByName()).isNull();
        assertThat(dto.paymentRecordedByName()).isNull();
    }

    @Test
    void anApproverWhoseAccountIsGone_stillRendersOnTheInvoice() {
        UUID id = UUID.randomUUID();
        when(invoiceRepo.findByIdWithLines(id))
                .thenReturn(Optional.of(invoice(id, SUBMITTER, DELETED_USER, null)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(SUBMITTER, "Anand Rajashekar", "anand@echno.test"))));

        ConstructionInvoiceDto dto = service.findById(id);

        assertThat(dto.approvedByName()).isEqualTo("User #" + DELETED_USER);
    }

    @Test
    void listingAPage_readsTheDirectoryOnceForEveryStampOnIt() {
        List<ConstructionInvoice> page = List.of(
                invoice(UUID.randomUUID(), SUBMITTER, APPROVER, null),
                invoice(UUID.randomUUID(), SUBMITTER, APPROVER, SUBMITTER),
                invoice(UUID.randomUUID(), DELETED_USER, APPROVER, null));
        when(invoiceRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(page));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(SUBMITTER, "Anand Rajashekar", "anand@echno.test"),
                new UserDisplayName(APPROVER, "Aneesh Johny", "aneesh@echno.test"))));

        List<ConstructionInvoiceDto> dtos =
                service.findAll(null, null, null, null, Pageable.unpaged()).getContent();

        ArgumentCaptor<Collection<Long>> asked = ArgumentCaptor.captor();
        verify(userNameDirectory, times(1)).namesFor(asked.capture());
        assertThat(asked.getValue()).contains(SUBMITTER, APPROVER, DELETED_USER);
        assertThat(dtos).extracting(ConstructionInvoiceDto::approvedByName)
                .containsExactly("Aneesh Johny", "Aneesh Johny", "Aneesh Johny");
        assertThat(dtos.get(2).submittedByName()).isEqualTo("User #" + DELETED_USER);
    }

    private ConstructionInvoice invoice(UUID id, Long submittedBy, Long approvedBy,
                                        Long paymentRecordedBy) {
        ConstructionInvoice invoice = new ConstructionInvoice();
        invoice.setId(id);
        invoice.setSubmittedBy(submittedBy);
        invoice.setApprovedBy(approvedBy);
        invoice.setPaymentRecordedBy(paymentRecordedBy);
        return invoice;
    }
}
