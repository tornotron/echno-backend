package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.employee.dto.EmployeeLookupDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The picker feed behind {@code GET /api/v1/employee/web/lookup}.
 *
 * <p>It used to read the whole employee table and map every row, which is the one shape a
 * dropdown never needs: the widget shows a handful of entries and the user narrows by typing.
 * It is now a search-and-limit read, and the two things worth pinning are that a caller cannot
 * talk it back into a whole-table read with a large {@code limit}, and that a blank search means
 * no filter rather than a filter on an empty string.
 *
 * <p>Only the collaborators this path touches are mocked. Mockito passes null for the rest of the
 * constructor, which is accurate: nothing else is reachable from the method under test.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeLookupTest {

    @Mock
    private EmployeeRepository employeeRepository;

    private EmployeeService service() {
        return new EmployeeService(employeeRepository, null, null, null, null, null, null);
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(employeeRepository).searchForLookup(any(), pageable.capture());
        return pageable.getValue();
    }

    private String captureSearchPattern() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(employeeRepository).searchForLookup(pattern.capture(), any());
        return pattern.getValue();
    }

    @Test
    void aLimitAboveTheCapIsClampedToIt() {
        when(employeeRepository.searchForLookup(any(), any())).thenReturn(Page.empty());

        service().lookupEmployees(null, 100_000);

        assertThat(capturePageable().getPageSize()).isEqualTo(UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void aLimitBelowOneIsRaisedToOne() {
        when(employeeRepository.searchForLookup(any(), any())).thenReturn(Page.empty());

        service().lookupEmployees(null, 0);

        assertThat(capturePageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void theFeedAlwaysReadsTheFirstPage() {
        when(employeeRepository.searchForLookup(any(), any())).thenReturn(Page.empty());

        service().lookupEmployees("mason", 25);

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(25);
    }

    @Test
    void aBlankSearchLeavesTheFilterOff() {
        when(employeeRepository.searchForLookup(isNull(), any())).thenReturn(Page.empty());

        service().lookupEmployees("   ", 50);

        assertThat(captureSearchPattern()).isNull();
    }

    @Test
    void aSearchTermIsLowercasedAndWrapped() {
        when(employeeRepository.searchForLookup(any(), any())).thenReturn(Page.empty());

        service().lookupEmployees("  Mason  ", 50);

        assertThat(captureSearchPattern()).isEqualTo("%mason%");
    }

    @Test
    void theFeedDoesNotSearchContactDetails() {
        when(employeeRepository.searchForLookup(any(), any())).thenReturn(Page.empty());

        service().lookupEmployees("mason", 50);

        // The restricted listing's search also matches email and phone. This feed is
        // readable by any tenant member, so it must not become a way of confirming a
        // guessed contact detail against a returned identity.
        verify(employeeRepository, never()).search(any(), any(), any(), any());
    }

    @Test
    void aRowIsProjectedWithoutContactOrSalaryDetail() {
        Employee employee = new Employee();
        employee.setId(7L);
        employee.setEmployeeId("EMP-007");
        employee.setEmployeeName("Mason");
        employee.setDesignation("Site engineer");
        when(employeeRepository.searchForLookup(any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(employee)));

        Page<EmployeeLookupDto> page = service().lookupEmployees(null, 50);

        assertThat(page.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(7L);
            assertThat(dto.employeeId()).isEqualTo("EMP-007");
            assertThat(dto.employeeName()).isEqualTo("Mason");
            assertThat(dto.organizationId()).isNull();
        });
    }
}
