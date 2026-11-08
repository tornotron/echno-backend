package org.tornotron.echno_backend.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.expense.ExpenseAccess;
import org.tornotron.echno_backend.expense.OneProjectExpenseAccess;
import org.tornotron.echno_backend.receipt.dto.ReceiptCreationDto;
import org.tornotron.echno_backend.receipt.dto.ReceiptUpdateDto;
import org.tornotron.echno_backend.receipt.mapper.ReceiptMapper;
import org.tornotron.echno_backend.organization.Organization;

/**
 * The receipt service asks the access matrix, under the expenses area, about each receipt, not only about
 * the area: a caller whose grants reach one project sees, records and changes only that project's receipts.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EntryNumberGenerator numberGenerator;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProjectExpenseAccess access = new OneProjectExpenseAccess(MY_PROJECT);
    private ReceiptService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        Employee caller = new Employee();
        caller.setId(7L);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        service = new ReceiptService(receiptRepository, receiptMapper, tenantEntityHelper, numberGenerator,
                new ExpenseAccess(access, currentEmployee));
        lenient().when(receiptRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG))
                .thenAnswer(call -> Optional.of(receipt(ON_MY_PROJECT, MY_PROJECT)));
        lenient().when(receiptRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG))
                .thenAnswer(call -> Optional.of(receipt(ELSEWHERE, OTHER_PROJECT)));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        lenient().when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aReceiptTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getById(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.update(ELSEWHERE, change(MY_PROJECT)));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.delete(ELSEWHERE));
    }

    @Test
    void aReceiptTheCallerMaySeeButNotDeleteIsRefusedAndKept() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.delete(ON_MY_PROJECT));
        verify(receiptRepository, never()).delete(any(Receipt.class));
    }

    @Test
    void aReceiptCannotBeMovedOutOfWhatTheCallerMayEdit() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.update(ON_MY_PROJECT, change(OTHER_PROJECT)));
        verify(receiptRepository, never()).saveAndFlush(any());

        service.update(ON_MY_PROJECT, change(MY_PROJECT));
        verify(receiptRepository).saveAndFlush(any());
    }

    @Test
    void aReceiptIsRecordedOnlyWhereTheCallerMayRecordOne() {
        service.create(record(MY_PROJECT));

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.create(record(OTHER_PROJECT)));
        verify(receiptRepository).saveAndFlush(any());
    }

    @Test
    void theListIsNarrowedInTheQuery() {
        when(receiptRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.getPaginated(0, 10, "client", "issued");

        verify(receiptRepository).findAll(any(Specification.class), any(Pageable.class));
        verify(receiptRepository, never()).search(any(), any(), any());
    }

    @Test
    void aCallerWhoMaySeeEveryReceiptGetsTheUnnarrowedSearch() {
        access.everything = true;
        when(receiptRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getPaginated(0, 10, "client", "issued");

        verify(receiptRepository).search("%client%", "issued",
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
        verify(receiptRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aCallerWhoMaySeeNoReceiptIsNotQueriedFor() {
        access.nothing = true;

        assertThat(service.getPaginated(0, 10, null, null)).isEmpty();
        verify(receiptRepository, never()).search(any(), any(), any());
        verify(receiptRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    private Receipt receipt(Long id, Long projectId) {
        Receipt receipt = new Receipt();
        receipt.setId(id);
        receipt.setOrganization(organization);
        receipt.setReceivedFrom("Payer " + id);
        receipt.setProjectId(projectId);
        return receipt;
    }

    private static ReceiptCreationDto record(Long projectId) {
        ReceiptCreationDto dto = new ReceiptCreationDto();
        dto.setReceivedFrom("Client");
        dto.setAmount(new BigDecimal("10.00"));
        dto.setProjectId(projectId);
        return dto;
    }

    private static ReceiptUpdateDto change(Long projectId) {
        ReceiptUpdateDto dto = new ReceiptUpdateDto();
        dto.setReceivedFrom("Client");
        dto.setAmount(new BigDecimal("10.00"));
        dto.setProjectId(projectId);
        return dto;
    }
}
