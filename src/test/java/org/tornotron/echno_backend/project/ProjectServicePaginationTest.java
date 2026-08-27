package org.tornotron.echno_backend.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paging and filter handling in {@link ProjectService#getProjectsPaginated}.
 *
 * <p>The endpoint this backs replaces a listing that silently truncated, so the two things worth
 * pinning are that it cannot be talked into an unbounded read by a large {@code pageSize}, and that
 * a wildcard in the search term matches a literal character rather than every row.
 *
 * <p>Only the collaborators this path touches are mocked. Mockito passes null for the rest of the
 * constructor, which is accurate: nothing else is reachable from the method under test.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServicePaginationTest {

    @Mock
    private ProjectRepository repository;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private ProjectService service;

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(any(), pageable.capture());
        return pageable.getValue();
    }

    private String captureSearchPattern() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(repository).search(pattern.capture(), any());
        return pattern.getValue();
    }

    @Test
    void aPageSizeAboveTheCapIsClampedToIt() {
        when(repository.search(any(), any())).thenReturn(Page.empty());

        service.getProjectsPaginated(0, 100_000, null);

        assertThat(capturePageable().getPageSize())
                .as("one request must not be able to re-create the unbounded read")
                .isEqualTo(UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void aPageSizeOfZeroIsRaisedToOneRatherThanRejectedByPageRequest() {
        when(repository.search(any(), any())).thenReturn(Page.empty());

        service.getProjectsPaginated(0, 0, null);

        assertThat(capturePageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void aNegativePageIndexIsTreatedAsTheFirstPage() {
        when(repository.search(any(), any())).thenReturn(Page.empty());

        service.getProjectsPaginated(-3, 20, null);

        assertThat(capturePageable().getPageNumber()).isZero();
    }

    @Test
    void theListingIsOrderedNewestFirst() {
        when(repository.search(any(), any())).thenReturn(Page.empty());

        service.getProjectsPaginated(0, 20, null);

        assertThat(capturePageable().getSort().getOrderFor("id"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void aBlankSearchTermDropsTheSearchClauseRatherThanMatchingNothing() {
        when(repository.search(isNull(), any())).thenReturn(Page.empty());

        service.getProjectsPaginated(0, 20, "   ");

        assertThat(captureSearchPattern()).isNull();
    }

    @Test
    void aSearchTermIsLowerCasedAndWrappedForALikeMatch() {
        when(repository.search(any(), any())).thenReturn(Page.empty());

        service.getProjectsPaginated(0, 20, "  Riverside  ");

        assertThat(captureSearchPattern()).isEqualTo("%riverside%");
    }

    @Test
    void aWildcardInTheSearchTermIsEscapedSoItMatchesItself() {
        when(repository.search(any(), any())).thenReturn(Page.empty());

        service.getProjectsPaginated(0, 20, "100%");

        assertThat(captureSearchPattern())
                .as("an unescaped % would turn a search into a full listing")
                .isEqualTo("%100\\%%");
    }

    @Test
    void anUnderscoreInTheSearchTermIsEscapedSoItIsNotASingleCharacterWildcard() {
        when(repository.search(any(), any())).thenReturn(Page.empty());

        service.getProjectsPaginated(0, 20, "block_a");

        assertThat(captureSearchPattern()).isEqualTo("%block\\_a%");
    }
}
