package org.tornotron.echno_backend.project;

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
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.project.dto.ProjectDto;

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
 * The listing behaviour of {@code GET /api/v1/project/web} and its paginated counterpart.
 *
 * <p>Guards the defect these tests were written for: the bare listing took a {@code pageSize} that
 * defaulted to ten and then answered with {@code page.getContent()}, so a caller that passed no
 * parameters received the ten lowest-id projects in the tenant. Every web caller passed no
 * parameters, and most of them are project pickers in forms, so the effect was that a user could
 * not select their own project unless it held one of those ten ids.
 *
 * <p>Deliberately the same tests as {@code TaskControllerWebListingTest}: the two listings share a
 * fix, and they should fail the same way if either regresses.
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerWebListingTest {

    @Mock
    private ProjectService service;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ProjectControllerWeb controller;

    private static List<ProjectDto> projects(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> {
                    ProjectDto dto = new ProjectDto();
                    dto.setId((long) i);
                    dto.setProjectName("Project " + i);
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
    private void serviceHolding(int projectCount) {
        List<ProjectDto> all = projects(projectCount);
        when(service.getAllProjects(anyInt(), anyInt())).thenAnswer(invocation -> {
            int pageNo = invocation.getArgument(0);
            int pageSize = invocation.getArgument(1);
            int from = Math.min(pageNo * pageSize, all.size());
            int to = Math.min(from + pageSize, all.size());
            return new PageImpl<>(all.subList(from, to), PageRequest.of(pageNo, pageSize), all.size());
        });
    }

    @Test
    void theBareListingDoesNotStopAtTheFirstTenProjects() {
        serviceHolding(12);

        ResponseEntity<List<ProjectDto>> response = controller.readAllProjects();

        assertThat(response.getBody())
                .as("a tenant with twelve projects must see twelve, not the first ten")
                .hasSize(12);
    }

    @Test
    void theBareListingReachesTheProjectsAPickerWouldOtherwiseNeverOffer() {
        serviceHolding(400);

        ResponseEntity<List<ProjectDto>> response = controller.readAllProjects();

        assertThat(response.getBody())
                .as("a form's project dropdown has to contain the project the user actually works on")
                .hasSize(400)
                .last()
                .extracting(ProjectDto::getId)
                .isEqualTo(400L);
    }

    @Test
    void theBareListingAsksForTheResultCapRatherThanAPageOfTen() {
        when(service.getAllProjects(anyInt(), anyInt())).thenReturn(Page.empty());

        controller.readAllProjects();

        ArgumentCaptor<Integer> pageNo = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> pageSize = ArgumentCaptor.forClass(Integer.class);
        verify(service).getAllProjects(pageNo.capture(), pageSize.capture());

        assertThat(pageNo.getValue()).isZero();
        assertThat(pageSize.getValue())
                .as("the ten-row default is what made the truncation silent")
                .isEqualTo(UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void theBareListingReportsTheTrueTotalOnEveryResponse() {
        serviceHolding(12);

        ResponseEntity<List<ProjectDto>> response = controller.readAllProjects();

        assertThat(response.getHeaders().getFirst(UnpagedResultCap.TOTAL_HEADER)).isEqualTo("12");
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.CAPPED_HEADER))
                .as("a complete result must not claim to be capped")
                .isNull();
    }

    @Test
    void theBareListingSaysSoWhenRowsWereLeftOut() {
        List<ProjectDto> capped = projects(UnpagedResultCap.MAX_ROWS);
        when(service.getAllProjects(anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(capped, PageRequest.of(0, UnpagedResultCap.MAX_ROWS), 900));

        ResponseEntity<List<ProjectDto>> response = controller.readAllProjects();

        assertThat(response.getHeaders().getFirst(UnpagedResultCap.CAPPED_HEADER))
                .as("a truncated result must describe itself rather than look complete")
                .isEqualTo("true");
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.TOTAL_HEADER)).isEqualTo("900");
    }

    @Test
    void thePaginatedListingKeepsThePageEnvelope() {
        Page<ProjectDto> page = new PageImpl<>(projects(20), PageRequest.of(1, 20), 137);
        when(service.getProjectsPaginated(eq(1), eq(20), isNull())).thenReturn(page);

        ResponseEntity<Page<ProjectDto>> response = controller.readAllProjectsPaginated(page(1, 20), null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalElements()).isEqualTo(137);
        assertThat(response.getBody().getNumber()).isEqualTo(1);
    }

    @Test
    void thePaginatedListingPassesTheSearchFilterThrough() {
        when(service.getProjectsPaginated(anyInt(), anyInt(), any())).thenReturn(Page.empty());

        controller.readAllProjectsPaginated(page(0, 20), "riverside");

        verify(service).getProjectsPaginated(0, 20, "riverside");
    }

    /** The page the request would have bound, built by hand for a direct controller call. */
    private static PageQuery page(int pageNo, int pageSize) {
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNo(pageNo);
        pageQuery.setPageSize(pageSize);
        return pageQuery;
    }
}
