package org.tornotron.echno_backend.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.tornotron.echno_backend.common.pagination.PageQuery20;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The listing behaviour of {@code GET /api/v1/tasks/web} and its paginated counterpart.
 *
 * <p>Guards the defect these tests were written for: the bare listing took a {@code pageSize} that
 * defaulted to ten and then answered with {@code page.getContent()}, so a caller that passed no
 * parameters received the ten lowest-id tasks in the tenant and nothing in the response said more
 * existed. Every web caller passed no parameters.
 *
 * <p>Plain JUnit and Mockito rather than a Spring slice: the behaviour under test is which page the
 * handler asks for and what it puts on the response, none of which needs a container. The test JVM
 * caches a context per distinct test configuration and is capped at 1 GB, so a context that buys
 * nothing is a context that costs an unrelated test its heap.
 */
@ExtendWith(MockitoExtension.class)
class TaskControllerWebListingTest {

    @Mock
    private TaskService service;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private TaskControllerWeb controller;

    private static List<TaskDto> tasks(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> {
                    TaskDto dto = new TaskDto();
                    dto.setId((long) i);
                    dto.setTitle("Task " + i);
                    return dto;
                })
                .toList();
    }

    /**
     * Stubs the service so it honours the page it was asked for, the way the repository does.
     *
     * <p>A stub that returns the whole list whatever page is requested would pass against the
     * truncating controller too, which is exactly the blind spot that let the defect ship: the
     * behaviour under test is the size of the page the handler asks for, so the stub has to be
     * sensitive to it.
     */
    private void serviceHolding(int taskCount) {
        List<TaskDto> all = tasks(taskCount);
        when(service.getAllTasks(anyInt(), anyInt())).thenAnswer(invocation -> {
            int pageNo = invocation.getArgument(0);
            int pageSize = invocation.getArgument(1);
            int from = Math.min(pageNo * pageSize, all.size());
            int to = Math.min(from + pageSize, all.size());
            return new PageImpl<>(all.subList(from, to), PageRequest.of(pageNo, pageSize), all.size());
        });
    }

    @Test
    void theBareListingDoesNotStopAtTheFirstTenTasks() {
        serviceHolding(12);

        ResponseEntity<List<TaskDto>> response = controller.readAllTasks();

        assertThat(response.getBody())
                .as("a tenant with twelve tasks must see twelve, not the first ten")
                .hasSize(12);
    }

    @Test
    void theBareListingReachesTasksWellPastTheOldTenRowPage() {
        serviceHolding(400);

        ResponseEntity<List<TaskDto>> response = controller.readAllTasks();

        assertThat(response.getBody())
                .as("the tasks a project owns are not necessarily the lowest ids in the tenant")
                .hasSize(400)
                .last()
                .extracting(TaskDto::getId)
                .isEqualTo(400L);
    }

    @Test
    void theBareListingAsksForTheResultCapRatherThanAPageOfTen() {
        when(service.getAllTasks(anyInt(), anyInt())).thenReturn(Page.empty());

        controller.readAllTasks();

        ArgumentCaptor<Integer> pageNo = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> pageSize = ArgumentCaptor.forClass(Integer.class);
        verify(service).getAllTasks(pageNo.capture(), pageSize.capture());

        assertThat(pageNo.getValue()).isZero();
        assertThat(pageSize.getValue())
                .as("the ten-row default is what made the truncation silent")
                .isEqualTo(UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void theBareListingReportsTheTrueTotalOnEveryResponse() {
        serviceHolding(12);

        ResponseEntity<List<TaskDto>> response = controller.readAllTasks();

        assertThat(response.getHeaders().getFirst(UnpagedResultCap.TOTAL_HEADER)).isEqualTo("12");
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.CAPPED_HEADER))
                .as("a complete result must not claim to be capped")
                .isNull();
    }

    @Test
    void theBareListingSaysSoWhenRowsWereLeftOut() {
        List<TaskDto> capped = tasks(UnpagedResultCap.MAX_ROWS);
        when(service.getAllTasks(anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(capped, PageRequest.of(0, UnpagedResultCap.MAX_ROWS), 900));

        ResponseEntity<List<TaskDto>> response = controller.readAllTasks();

        assertThat(response.getHeaders().getFirst(UnpagedResultCap.CAPPED_HEADER))
                .as("a truncated result must describe itself rather than look complete")
                .isEqualTo("true");
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.TOTAL_HEADER)).isEqualTo("900");
    }

    @Test
    void thePaginatedListingKeepsThePageEnvelope() {
        Page<TaskDto> page = new PageImpl<>(tasks(20), PageRequest.of(1, 20), 137);
        when(service.getTasksPaginated(eq(1), eq(20), isNull(), isNull())).thenReturn(page);

        ResponseEntity<Page<TaskDto>> response = controller.readAllTasksPaginated(page(1, 20), null, null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalElements()).isEqualTo(137);
        assertThat(response.getBody().getNumber()).isEqualTo(1);
    }

    @Test
    void thePaginatedListingPassesTheProjectAndSearchFiltersThrough() {
        when(service.getTasksPaginated(anyInt(), anyInt(), any(), any())).thenReturn(Page.empty());

        controller.readAllTasksPaginated(page(0, 20), 7L, "slab");

        verify(service).getTasksPaginated(0, 20, 7L, "slab");
    }

    /** The page the request would have bound, built by hand for a direct controller call. */
    private static PageQuery20 page(int pageNo, int pageSize) {
        PageQuery20 pageQuery = new PageQuery20();
        pageQuery.setPageNo(pageNo);
        pageQuery.setPageSize(pageSize);
        return pageQuery;
    }
}
