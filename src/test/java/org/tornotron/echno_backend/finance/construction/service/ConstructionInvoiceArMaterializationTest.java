package org.tornotron.echno_backend.finance.construction.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoiceLine;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapper;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceRepository;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.dtos.CreateInvoiceRequest;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsService;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AR route a sales or service construction invoice takes on approval.
 * Everything the service talks to is mocked, so what is pinned here is the routing and the
 * line mapping: an invoice on a project with a client raises a real AR invoice and adopts
 * its journal entry, a discounted line is billed at its net so the two documents agree,
 * cancelling unwinds through the AR invoice on the internal call that the AR module's own
 * refusal does not block, and an absent, unknown or inactive client falls back to posting
 * the receivable directly. The totals the two documents must agree on are reconciled in
 * ConstructionInvoicePostingIT, where both are real.
 */
@ExtendWith(MockitoExtension.class)
class ConstructionInvoiceArMaterializationTest {

    private static final long ORG_ID = 9L;
    private static final long PROJECT_ID = 41L;

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

    private ConstructionInvoiceService service;

    private final UUID arInvoiceId = UUID.randomUUID();
    private final UUID arJournalEntryId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID revenueAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new ConstructionInvoiceService(invoiceRepo, numberGen, mapper, tenantEntityHelper,
                journalRepo, postingService, postingAccountResolver, financeSettingsService,
                projectRepository, userContextService, costCategoryRepository, customerRepository,
                invoiceService);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void approve_salesInvoiceOnAProjectWithAClient_raisesTheArInvoiceAndAdoptsItsEntry() {
        ConstructionInvoice inv = pendingSalesInvoice(line("Piling works", "10", "1000", "18", "0"));
        givenProjectClient(activeCustomer());
        givenArInvoiceIssued();

        service.approve(inv.getId());

        assertThat(inv.getStatus()).isEqualTo(ConstructionInvoiceStatus.APPROVED);
        assertThat(inv.getArInvoiceId()).isEqualTo(arInvoiceId);
        assertThat(inv.getJournalEntryId()).isEqualTo(arJournalEntryId);
        verify(postingService, never()).postInternal(any(), anyString(), any());

        CreateInvoiceRequest req = capturedArRequest();
        assertThat(req.customerId()).isEqualTo(customerId);
        assertThat(req.invoiceDate()).isEqualTo(inv.getIssueDate());
        assertThat(req.dueDate()).isEqualTo(inv.getDueDate());
        assertThat(req.notes()).contains(inv.getInvoiceNumber());
        assertThat(req.lines()).singleElement().satisfies(l -> {
            assertThat(l.description()).isEqualTo("Piling works");
            assertThat(l.quantity()).isEqualByComparingTo("10");
            assertThat(l.unitPrice()).isEqualByComparingTo("1000");
            assertThat(l.taxRate()).isEqualByComparingTo("18");
            assertThat(l.revenueAccountId()).isEqualTo(revenueAccountId);
        });
    }

    @Test
    void approve_discountedLine_isBilledAtItsNetSoTheTwoDocumentsAgree() {
        // 10 x 1000 = 10000 subtotal, 5% discount = 500, so the AR line bills 9500 as one unit.
        ConstructionInvoice inv = pendingSalesInvoice(line("Piling works", "10", "1000", "18", "5"));
        givenProjectClient(activeCustomer());
        givenArInvoiceIssued();

        service.approve(inv.getId());

        assertThat(capturedArRequest().lines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualByComparingTo("1");
            assertThat(l.unitPrice()).isEqualByComparingTo("9500");
            assertThat(l.taxRate()).isEqualByComparingTo("18");
        });
    }

    @Test
    void approve_projectWithoutAClient_postsTheReceivableDirectly() {
        ConstructionInvoice inv = pendingSalesInvoice(line("Piling works", "10", "1000", "18", "0"));
        givenProject(null);
        givenDirectPostingAccounts();

        service.approve(inv.getId());

        assertThat(inv.getArInvoiceId()).isNull();
        assertThat(inv.getStatus()).isEqualTo(ConstructionInvoiceStatus.APPROVED);
        verify(invoiceService, never()).createDraft(any());
        verify(postingService).postInternal(any(PostJournalRequest.class), eq("CONSTRUCTION_INVOICE"),
                eq(inv.getId()));
    }

    @Test
    void approve_inactiveClient_fallsBackToPostingTheReceivableDirectly() {
        ConstructionInvoice inv = pendingSalesInvoice(line("Piling works", "10", "1000", "18", "0"));
        Customer inactive = activeCustomer();
        inactive.setActive(false);
        givenProjectClient(inactive);
        givenDirectPostingAccounts();

        service.approve(inv.getId());

        assertThat(inv.getArInvoiceId()).isNull();
        verify(invoiceService, never()).createDraft(any());
        verify(postingService).postInternal(any(PostJournalRequest.class), anyString(), any());
    }

    @Test
    void cancel_invoiceWithAMaterializedArInvoice_unwindsThroughIt() {
        ConstructionInvoice inv = pendingSalesInvoice(line("Piling works", "10", "1000", "18", "0"));
        inv.setStatus(ConstructionInvoiceStatus.APPROVED);
        inv.setArInvoiceId(arInvoiceId);
        inv.setJournalEntryId(arJournalEntryId);
        UUID reversalId = UUID.randomUUID();
        when(invoiceService.cancelInternal(arInvoiceId, "Client withdrew the claim"))
                .thenReturn(arInvoiceDto(arJournalEntryId, reversalId));

        service.cancel(inv.getId(), "Client withdrew the claim");

        assertThat(inv.getStatus()).isEqualTo(ConstructionInvoiceStatus.CANCELLED);
        assertThat(inv.getReversalJournalEntryId()).isEqualTo(reversalId);
        verify(postingService, never()).reverse(any(), any());
    }

    private ConstructionInvoice pendingSalesInvoice(ConstructionInvoiceLine line) {
        ConstructionInvoice inv = new ConstructionInvoice();
        inv.setId(UUID.randomUUID());
        inv.setInvoiceNumber("CINV-2026-0007");
        inv.setType(ConstructionInvoiceType.SALES);
        inv.setStatus(ConstructionInvoiceStatus.PENDING);
        inv.setProjectId(PROJECT_ID);
        inv.setIssueDate(LocalDate.of(2026, 8, 1));
        inv.setDueDate(LocalDate.of(2026, 8, 31));
        inv.setSubtotal(line.getSubtotal());
        inv.setTaxAmount(line.getTaxAmount());
        inv.setDiscountAmount(line.getDiscountAmount());
        inv.setTotalAmount(line.getTotal());
        inv.addLine(line);
        when(invoiceRepo.findByIdWithLines(inv.getId())).thenReturn(Optional.of(inv));
        return inv;
    }

    /** Builds a line the way {@code applyLinesAndTotals} would, from the same inputs. */
    private ConstructionInvoiceLine line(String description, String quantity, String unitPrice,
                                         String taxRate, String discountRate) {
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal price = new BigDecimal(unitPrice);
        BigDecimal subtotal = qty.multiply(price);
        BigDecimal discount = subtotal.multiply(new BigDecimal(discountRate))
                .divide(BigDecimal.valueOf(100));
        BigDecimal tax = subtotal.subtract(discount).multiply(new BigDecimal(taxRate))
                .divide(BigDecimal.valueOf(100));

        ConstructionInvoiceLine line = new ConstructionInvoiceLine();
        line.setDescription(description);
        line.setQuantity(qty);
        line.setUnit("lot");
        line.setUnitPrice(price);
        line.setTaxRate(new BigDecimal(taxRate));
        line.setTaxAmount(tax);
        line.setDiscountRate(new BigDecimal(discountRate));
        line.setDiscountAmount(discount);
        line.setSubtotal(subtotal);
        line.setTotal(subtotal.add(tax).subtract(discount));
        return line;
    }

    private Customer activeCustomer() {
        Customer customer = new Customer();
        customer.setId(customerId);
        customer.setCode("CUST-001");
        customer.setName("Asset Homes Pvt Ltd");
        customer.setActive(true);
        return customer;
    }

    private void givenProject(UUID clientId) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setCustomerId(clientId);
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project));
    }

    private void givenProjectClient(Customer customer) {
        givenProject(customer.getId());
        when(customerRepository.findScopedById(customer.getId())).thenReturn(Optional.of(customer));
    }

    private void givenArInvoiceIssued() {
        when(postingAccountResolver.resolve(PostingRole.DEFAULT_REVENUE))
                .thenReturn(account(revenueAccountId));
        UUID draftId = UUID.randomUUID();
        when(invoiceService.createDraft(any(CreateInvoiceRequest.class)))
                .thenReturn(arInvoiceDto(null, null, draftId));
        when(invoiceService.issue(draftId)).thenReturn(arInvoiceDto(arJournalEntryId, null));
    }

    private void givenDirectPostingAccounts() {
        lenient().when(postingAccountResolver.resolve(any(PostingRole.class)))
                .thenAnswer(call -> account(UUID.randomUUID()));
        JournalEntry je = new JournalEntry();
        je.setId(UUID.randomUUID());
        je.setEntryNumber("JE-2026-0100");
        when(postingService.postInternal(any(PostJournalRequest.class), anyString(), any()))
                .thenReturn(je);
    }

    private CreateInvoiceRequest capturedArRequest() {
        ArgumentCaptor<CreateInvoiceRequest> captor = ArgumentCaptor.forClass(CreateInvoiceRequest.class);
        verify(invoiceService).createDraft(captor.capture());
        return captor.getValue();
    }

    private Account account(UUID id) {
        Account account = new Account();
        account.setId(id);
        account.setCode("4000");
        return account;
    }

    private InvoiceDto arInvoiceDto(UUID journalEntryId, UUID reversalJournalEntryId) {
        return arInvoiceDto(journalEntryId, reversalJournalEntryId, arInvoiceId);
    }

    private InvoiceDto arInvoiceDto(UUID journalEntryId, UUID reversalJournalEntryId, UUID id) {
        return new InvoiceDto(id, "INV-2026-0011", customerId, "Asset Homes Pvt Ltd",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), InvoiceStatus.ISSUED,
                new BigDecimal("10000"), new BigDecimal("1800"), new BigDecimal("11800"),
                BigDecimal.ZERO, new BigDecimal("11800"), journalEntryId, reversalJournalEntryId,
                null, List.of());
    }
}
