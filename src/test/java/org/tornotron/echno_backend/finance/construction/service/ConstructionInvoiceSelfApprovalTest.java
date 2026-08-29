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
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapper;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceRepository;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsService;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Segregation of duties on the construction-invoice approval, which posts a journal entry and is
 * the other half of the same rule the stock adjustment carries: whoever submitted the invoice
 * cannot approve it, a system administrator may as a recorded exception, and anybody else
 * approves as normal. The auto-approval inside submit is deliberately outside the rule and is
 * covered by ConstructionInvoiceAutoApprovalIT.
 */
@ExtendWith(MockitoExtension.class)
class ConstructionInvoiceSelfApprovalTest {

    private static final long ORG_ID = 9L;
    private static final long SUBMITTER = 31L;
    private static final long OTHER_APPROVER = 32L;

    @Mock private ConstructionInvoiceRepository invoiceRepo;
    @Mock private EntryNumberGenerator numberGen;
    @Mock private ConstructionInvoiceMapper mapper;
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

    private ConstructionInvoiceService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new ConstructionInvoiceService(invoiceRepo, numberGen, mapper, tenantEntityHelper,
                journalRepo, postingService, postingAccountResolver, financeSettingsService,
                projectRepository, userContextService, costCategoryRepository, customerRepository,
                invoiceService, new SelfApprovalPolicy(orgSecurity));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void theSubmitterCannotApproveTheirOwnInvoiceAndNoEntryIsPosted() {
        ConstructionInvoice inv = pendingPurchaseInvoice();
        when(userContextService.getCurrentUserId()).thenReturn(SUBMITTER);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(inv.getId()))
                .withMessageContaining(inv.getInvoiceNumber())
                .withMessageContaining("someone other than whoever raised the document");

        verify(postingService, never()).postInternal(any(), anyString(), any());
        assertThat(inv.getStatus()).isEqualTo(ConstructionInvoiceStatus.PENDING);
        assertThat(inv.getApprovedBy()).isNull();
    }

    @Test
    void aSystemAdministratorMayApproveTheirOwnInvoice() {
        ConstructionInvoice inv = pendingPurchaseInvoice();
        when(userContextService.getCurrentUserId()).thenReturn(SUBMITTER);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(true);
        givenPostingAccounts();

        service.approve(inv.getId());

        assertThat(inv.getStatus()).isEqualTo(ConstructionInvoiceStatus.APPROVED);
        assertThat(inv.getApprovedBy()).isEqualTo(SUBMITTER);
    }

    @Test
    void anotherApproverIsUnaffected() {
        ConstructionInvoice inv = pendingPurchaseInvoice();
        when(userContextService.getCurrentUserId()).thenReturn(OTHER_APPROVER);
        givenPostingAccounts();

        service.approve(inv.getId());

        assertThat(inv.getStatus()).isEqualTo(ConstructionInvoiceStatus.APPROVED);
        assertThat(inv.getApprovedBy()).isEqualTo(OTHER_APPROVER);
        verify(orgSecurity, never()).hasAnyOrgRoleForCurrentTenant(anyString());
    }

    private ConstructionInvoice pendingPurchaseInvoice() {
        ConstructionInvoice inv = new ConstructionInvoice();
        inv.setId(UUID.randomUUID());
        inv.setInvoiceNumber("CINV-2026-0007");
        inv.setType(ConstructionInvoiceType.PURCHASE);
        inv.setStatus(ConstructionInvoiceStatus.PENDING);
        inv.setIssueDate(LocalDate.of(2026, 8, 1));
        inv.setDueDate(LocalDate.of(2026, 8, 31));
        inv.setSubtotal(new BigDecimal("1000"));
        inv.setTaxAmount(BigDecimal.ZERO);
        inv.setDiscountAmount(BigDecimal.ZERO);
        inv.setTotalAmount(new BigDecimal("1000"));
        inv.setSubmittedBy(SUBMITTER);
        when(invoiceRepo.findByIdWithLines(inv.getId())).thenReturn(Optional.of(inv));
        return inv;
    }

    private void givenPostingAccounts() {
        lenient().when(postingAccountResolver.resolve(any(PostingRole.class)))
                .thenAnswer(call -> account());
        JournalEntry je = new JournalEntry();
        je.setId(UUID.randomUUID());
        je.setEntryNumber("JE-2026-0100");
        when(postingService.postInternal(any(PostJournalRequest.class), anyString(), any()))
                .thenReturn(je);
    }

    private Account account() {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setCode("5000");
        return account;
    }
}
