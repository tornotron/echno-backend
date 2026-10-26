package org.tornotron.echno_backend.modules.inspections.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.inspections.RegionShape;

class RegionGeometryTest {

    @Test
    void boxIsRoundedToSixPlacesAndKeptAsItsOwnBoundingBox() {
        RegionGeometry.Normalised box = RegionGeometry.normalise("r", RegionShape.BOX,
                List.of(d("0.4120004"), d("0.188"), d("0.471"), d("0.2625")), null);

        assertThat(box.box()).containsExactly(d("0.412000"), d("0.188000"), d("0.471000"), d("0.262500"));
        assertThat(box.points()).isNull();
    }

    @Test
    void boxOutsideTheImageOrInvertedOrCollapsedIsRefused() {
        assertInvalid(RegionShape.BOX, List.of(d("-0.01"), d("0.1"), d("0.2"), d("0.3")), null, "[0, 1]");
        assertInvalid(RegionShape.BOX, List.of(d("0.1"), d("0.1"), d("1.0000006"), d("0.3")), null, "[0, 1]");
        assertInvalid(RegionShape.BOX, List.of(d("0.5"), d("0.1"), d("0.4"), d("0.3")), null, "x1 < x2");
        // equal after rounding to six places
        assertInvalid(RegionShape.BOX, List.of(d("0.1"), d("0.2"), d("0.3"), d("0.2000001")), null, "y1 < y2");
        assertInvalid(RegionShape.BOX, List.of(d("0.1"), d("0.2"), d("0.3")), null, "four values");
        assertInvalid(RegionShape.BOX, List.of(d("0.1"), d("0.2"), d("0.3"), d("0.4")),
                List.of(pt("0.1", "0.1")), "not points");
    }

    @Test
    void polygonStoresOpenVerticesAndItsBoundingBox() {
        RegionGeometry.Normalised p = RegionGeometry.normalise("r", RegionShape.POLYGON, null,
                List.of(pt("0.10", "0.52"), pt("0.18", "0.49"), pt("0.21", "0.61"), pt("0.10", "0.52")));

        assertThat(p.points()).hasSize(3);
        assertThat(p.box()).containsExactly(d("0.100000"), d("0.490000"), d("0.210000"), d("0.610000"));
    }

    @Test
    void polygonVertexCountIsThreeToTwoHundred() {
        assertInvalid(RegionShape.POLYGON, null, List.of(pt("0.1", "0.1"), pt("0.2", "0.2")), "3 to 200");
        assertThat(RegionGeometry.normalise("r", RegionShape.POLYGON, null, circle(200)).points()).hasSize(200);
        assertInvalid(RegionShape.POLYGON, null, circle(201), "3 to 200");
    }

    @Test
    void selfIntersectingOrDegeneratePolygonIsRefused() {
        // a bow tie: edges 0 and 2 cross
        assertInvalid(RegionShape.POLYGON, null,
                List.of(pt("0.1", "0.1"), pt("0.5", "0.5"), pt("0.5", "0.1"), pt("0.1", "0.5")), "cross");
        // all on one line
        assertInvalid(RegionShape.POLYGON, null,
                List.of(pt("0.1", "0.1"), pt("0.2", "0.2"), pt("0.3", "0.3")), "INVALID_GEOMETRY");
        // a spike that doubles back along its own edge
        assertInvalid(RegionShape.POLYGON, null,
                List.of(pt("0.1", "0.1"), pt("0.5", "0.1"), pt("0.3", "0.1"), pt("0.3", "0.4")), "INVALID_GEOMETRY");
        // a vertex touching a non-adjacent edge
        assertInvalid(RegionShape.POLYGON, null,
                List.of(pt("0.1", "0.1"), pt("0.5", "0.1"), pt("0.5", "0.5"), pt("0.3", "0.1"), pt("0.1", "0.5")),
                "INVALID_GEOMETRY");
        assertInvalid(RegionShape.POLYGON, d4(), null, "derived");
    }

    @Test
    void aConcavePolygonIsAccepted() {
        assertThat(RegionGeometry.normalise("r", RegionShape.POLYGON, null,
                List.of(pt("0.1", "0.1"), pt("0.5", "0.1"), pt("0.3", "0.2"), pt("0.5", "0.5"), pt("0.1", "0.5")))
                .points()).hasSize(5);
    }

    private static void assertInvalid(RegionShape shape, List<BigDecimal> box, List<List<BigDecimal>> points,
                                      String message) {
        assertThatThrownBy(() -> RegionGeometry.normalise("regions[0]", shape, box, points))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("regions[0]")
                .hasMessageContaining(message);
    }

    private static List<List<BigDecimal>> circle(int n) {
        List<List<BigDecimal>> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            points.add(List.of(BigDecimal.valueOf(0.5 + 0.4 * Math.cos(a)), BigDecimal.valueOf(0.5 + 0.4 * Math.sin(a))));
        }
        return points;
    }

    private static List<BigDecimal> d4() {
        return List.of(d("0.1"), d("0.1"), d("0.2"), d("0.2"));
    }

    private static List<BigDecimal> pt(String x, String y) {
        return List.of(d(x), d(y));
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }
}
