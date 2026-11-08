package org.tornotron.echno_backend.expense;

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
import org.tornotron.echno_backend.expense.dto.ExpenseCreationDto;
import org.tornotron.echno_backend.expense.dto.ExpenseUpdateDto;
import org.tornotron.echno_backend.expense.mapper.ExpenseMapper;
import org.tornotron.echno_backend.organization.Organization;

/**
 * The expense service asks the access matrix about each expense, not only about the area: a caller whose
 * grants reach one project sees, records and changes only that project's expenses.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;

    @Mock private ExpenseRepository expenseRepository;
    @Mock private ExpenseMapper expenseMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EntryNumberGenerator numberGenerator;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProjectExpenseAccess access = new OneProjectExpenseAccess(MY_PROJECT);
    private ExpenseService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        Employee caller = new Employee();
        caller.setId(7L);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        service = new ExpenseService(expenseRepository, expenseMapper, tenantEntityHelper, numberGenerator,
                new ExpenseAccess(access, currentEmployee));
        lenient().when(expenseRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG))
                .thenAnswer(call -> Optional.of(expense(ON_MY_PROJECT, MY_PROJECT)));
        lenient().when(expenseRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG))
                .thenAnswer(call -> Optional.of(expense(ELSEWHERE, OTHER_PROJECT)));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        lenient().when(expenseRepository.saveAndFlush(any(Expense.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anExpenseTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getById(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.update(ELSEWHERE, change(MY_PROJECT)));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.delete(ELSEWHERE));
    }

    @Test
    void anExpenseTheCallerMaySeeButNotDeleteIsRefusedAndKept() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.delete(ON_MY_PROJECT));
        verify(expenseRepository, never()).delete(any(Expense.class));
    }

    @Test
    void anExpenseCannotBeMovedOutOfWhatTheCallerMayEdit() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.update(ON_MY_PROJECT, change(OTHER_PROJECT)));
        verify(expenseRepository, never()).saveAndFlush(any());

        service.update(ON_MY_PROJECT, change(MY_PROJECT));
        verify(expenseRepository).saveAndFlush(any());
    }

    @Test
    void anExpenseIsRecordedOnlyWhereTheCallerMayRecordOne() {
        service.create(record(MY_PROJECT));

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.create(record(OTHER_PROJECT)));
        verify(expenseRepository).saveAndFlush(any());
    }

    @Test
    void theListIsNarrowedInTheQuery() {
        when(expenseRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.getPaginated(0, 10, "cement", "paid");

        verify(expenseRepository).findAll(any(Specification.class), any(Pageable.class));
        verify(expenseRepository, never()).search(any(), any(), any());
    }

    @Test
    void aCallerWhoMaySeeEveryExpenseGetsTheUnnarrowedSearch() {
        access.everything = true;
        when(expenseRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getPaginated(0, 10, "cement", "paid");

        verify(expenseRepository).search("%cement%", "paid",
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
        verify(expenseRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aCallerWhoMaySeeNoExpenseIsNotQueriedFor() {
        access.nothing = true;

        assertThat(service.getPaginated(0, 10, null, null)).isEmpty();
        verify(expenseRepository, never()).search(any(), any(), any());
        verify(expenseRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    private Expense expense(Long id, Long projectId) {
        Expense expense = new Expense();
        expense.setId(id);
        expense.setOrganization(organization);
        expense.setDescription("Expense " + id);
        expense.setProjectId(projectId);
        return expense;
    }

    private static ExpenseCreationDto record(Long projectId) {
        ExpenseCreationDto dto = new ExpenseCreationDto();
        dto.setDescription("Cement");
        dto.setAmount(new BigDecimal("10.00"));
        dto.setProjectId(projectId);
        return dto;
    }

    private static ExpenseUpdateDto change(Long projectId) {
        ExpenseUpdateDto dto = new ExpenseUpdateDto();
        dto.setDescription("Cement");
        dto.setAmount(new BigDecimal("10.00"));
        dto.setProjectId(projectId);
        return dto;
    }
}
