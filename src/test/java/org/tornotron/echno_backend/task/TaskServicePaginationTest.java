package org.tornotron.echno_backend.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.mapper.TaskMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Paging and filter handling in {@link TaskService#getTasksPaginated}.
 *
 * <p>The endpoint this backs replaces a listing that silently truncated, so the two things worth
 * pinning are that it cannot be talked into an unbounded read by a large {@code pageSize}, and that
 * a wildcard in the search term matches a literal character rather than every row.
 */
@ExtendWith(MockitoExtension.class)
class TaskServicePaginationTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private TaskMapper taskMapper;

    @InjectMocks
    private TaskService service;

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(taskRepository).search(any(), any(), pageable.capture());
        return pageable.getValue();
    }

    private String captureSearchPattern() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(taskRepository).search(any(), pattern.capture(), any());
        return pattern.getValue();
    }

    @Test
    void aPageSizeAboveTheCapIsClampedToIt() {
        when(taskRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getTasksPaginated(0, 100_000, null, null);

        assertThat(capturePageable().getPageSize())
                .as("one request must not be able to re-create the unbounded read")
                .isEqualTo(UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void aPageSizeOfZeroIsRaisedToOneRatherThanRejectedByPageRequest() {
        when(taskRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getTasksPaginated(0, 0, null, null);

        assertThat(capturePageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void aNegativePageIndexIsTreatedAsTheFirstPage() {
        when(taskRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getTasksPaginated(-3, 20, null, null);

        assertThat(capturePageable().getPageNumber()).isZero();
    }

    @Test
    void theListingIsOrderedNewestFirst() {
        when(taskRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getTasksPaginated(0, 20, null, null);

        assertThat(capturePageable().getSort().getOrderFor("id"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void aBlankSearchTermDropsTheSearchClauseRatherThanMatchingNothing() {
        when(taskRepository.search(any(), isNull(), any())).thenReturn(Page.empty());

        service.getTasksPaginated(0, 20, null, "   ");

        assertThat(captureSearchPattern()).isNull();
    }

    @Test
    void aSearchTermIsLowerCasedAndWrappedForALikeMatch() {
        when(taskRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getTasksPaginated(0, 20, null, "  Slab  ");

        assertThat(captureSearchPattern()).isEqualTo("%slab%");
    }

    @Test
    void aWildcardInTheSearchTermIsEscapedSoItMatchesItself() {
        when(taskRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getTasksPaginated(0, 20, null, "100%");

        assertThat(captureSearchPattern())
                .as("an unescaped % would turn a search into a full listing")
                .isEqualTo("%100\\%%");
    }

    @Test
    void anUnderscoreInTheSearchTermIsEscapedSoItIsNotASingleCharacterWildcard() {
        when(taskRepository.search(any(), any(), any())).thenReturn(Page.empty());

        service.getTasksPaginated(0, 20, null, "block_a");

        assertThat(captureSearchPattern()).isEqualTo("%block\\_a%");
    }
}
