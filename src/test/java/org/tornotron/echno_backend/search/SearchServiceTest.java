package org.tornotron.echno_backend.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Term handling and bounding in {@link SearchService}.
 *
 * <p>This endpoint exists to replace whole-collection reads, so what matters is that it cannot be
 * talked back into one: a term too short to be selective never reaches the database, and the row
 * limit is the service's to decide rather than the caller's.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private SearchRepository searchRepository;

    @InjectMocks
    private SearchService service;

    private void repositoryReturningNothing() {
        when(searchRepository.findProjects(anyString(), any())).thenReturn(List.of());
        when(searchRepository.findTasks(anyString(), any())).thenReturn(List.of());
        when(searchRepository.findIssues(anyString(), any())).thenReturn(List.of());
    }

    private String capturedPattern() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(searchRepository).findProjects(pattern.capture(), any());
        return pattern.getValue();
    }

    private Pageable capturedRows() {
        ArgumentCaptor<Pageable> rows = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(searchRepository).findProjects(anyString(), rows.capture());
        return rows.getValue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "a", " b "})
    void aTermTooShortToBeSelectiveNeverReachesTheDatabase(String term) {
        assertThat(service.search(term, null)).isEmpty();
        verifyNoInteractions(searchRepository);
    }

    @Test
    void aTermIsLowerCasedAndWrappedForALikeMatch() {
        repositoryReturningNothing();

        service.search("  Slab  ", null);

        assertThat(capturedPattern()).isEqualTo("%slab%");
    }

    @Test
    void aWildcardInTheTermIsEscapedSoItMatchesItself() {
        repositoryReturningNothing();

        service.search("100%", null);

        assertThat(capturedPattern())
                .as("an unescaped % would turn a search into a full listing")
                .isEqualTo("%100\\%%");
    }

    @Test
    void anUnderscoreInTheTermIsEscapedSoItIsNotASingleCharacterWildcard() {
        repositoryReturningNothing();

        service.search("block_a", null);

        assertThat(capturedPattern()).isEqualTo("%block\\_a%");
    }

    @Test
    void aMissingLimitFallsBackToTheDefault() {
        repositoryReturningNothing();

        service.search("slab", null);

        assertThat(capturedRows().getPageSize()).isEqualTo(SearchService.DEFAULT_LIMIT);
    }

    @Test
    void aLimitAboveTheCeilingIsClampedToIt() {
        repositoryReturningNothing();

        service.search("slab", 100_000);

        assertThat(capturedRows().getPageSize())
                .as("the caller must not be able to ask this endpoint for the whole tenant")
                .isEqualTo(SearchService.MAX_LIMIT);
    }

    @Test
    void aLimitOfZeroIsRaisedToOneRatherThanRejectedByPageRequest() {
        repositoryReturningNothing();

        service.search("slab", 0);

        assertThat(capturedRows().getPageSize()).isEqualTo(1);
    }

    @Test
    void hitsFromAllThreeKindsComeBackTogetherWithProjectsFirst() {
        when(searchRepository.findProjects(anyString(), any()))
                .thenReturn(List.of(new SearchHit(SearchHitType.PROJECT, 1L, "Tower A", 1L)));
        when(searchRepository.findTasks(anyString(), any()))
                .thenReturn(List.of(new SearchHit(SearchHitType.TASK, 8L, "Tower slab", 1L)));
        when(searchRepository.findIssues(anyString(), any()))
                .thenReturn(List.of(new SearchHit(SearchHitType.ISSUE, 4L, "Tower crack", 1L)));

        List<SearchHit> hits = service.search("tower", null);

        assertThat(hits).extracting(SearchHit::type).containsExactly(
                SearchHitType.PROJECT, SearchHitType.TASK, SearchHitType.ISSUE);
        assertThat(hits).extracting(SearchHit::projectId)
                .as("a hit carries the project it hangs off so a link needs no second lookup")
                .containsOnly(1L);
    }
}
