package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which handler {@code /api/v1/ncrs/web/punch-list/pdf} reaches.
 *
 * <p>The punch list sits alongside {@code /{id}/pdf}, and both patterns match that
 * request. Spring resolves it by specificity, which sorts a literal segment ahead
 * of a template variable, so the punch list wins and "punch-list" is never offered
 * to the UUID converter. If that ordering were the other way round the endpoint
 * would answer 400 on a bad UUID rather than returning a document, which is a
 * failure no compiler and no guard test would catch.
 *
 * <p>Asserted against {@code PathPattern}'s own comparator, the same one the
 * mapping uses, rather than through a web slice: a {@code @WebMvcTest} would cost
 * the 1 GB test JVM another cached context to establish the same fact.
 */
class NcrReportRoutingTest {

    private static final String REQUEST = "/api/v1/ncrs/web/punch-list/pdf";

    @Test
    void thePunchListWinsOverTheSingleReportPattern() {
        PathPatternParser parser = new PathPatternParser();
        PathPattern punchList = parser.parse("/api/v1/ncrs/web/punch-list/pdf");
        PathPattern singleReport = parser.parse("/api/v1/ncrs/web/{id}/pdf");
        PathContainer path = PathContainer.parsePath(REQUEST);

        assertThat(punchList.matches(path)).isTrue();
        assertThat(singleReport.matches(path))
                .as("both patterns match, which is why the ordering has to be asserted")
                .isTrue();

        List<PathPattern> ordered = new ArrayList<>(List.of(singleReport, punchList));
        ordered.sort(PathPattern.SPECIFICITY_COMPARATOR);

        assertThat(ordered.getFirst()).isEqualTo(punchList);
    }

    @Test
    void anActualReportIdStillReachesTheSingleReportPattern() {
        PathPatternParser parser = new PathPatternParser();
        PathPattern punchList = parser.parse("/api/v1/ncrs/web/punch-list/pdf");
        PathPattern singleReport = parser.parse("/api/v1/ncrs/web/{id}/pdf");
        PathContainer path = PathContainer.parsePath(
                "/api/v1/ncrs/web/12121212-3434-5656-7878-909090909090/pdf");

        assertThat(punchList.matches(path)).isFalse();
        assertThat(singleReport.matches(path)).isTrue();
    }
}
