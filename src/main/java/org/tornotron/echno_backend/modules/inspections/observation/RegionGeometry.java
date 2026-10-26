package org.tornotron.echno_backend.modules.inspections.observation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.inspections.RegionShape;
import org.tornotron.echno_backend.modules.inspections.domain.DefectPhotoAnnotation;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationRegion;

/**
 * Validates and normalises the geometry of an observation region, as posted by a producer, a
 * person or a reviewer. Every coordinate is rounded to the stored scale first and checked
 * after, so what is validated is exactly what is written.
 *
 * <p>A box is {@code [x1, y1, x2, y2]} with {@code x1 < x2} and {@code y1 < y2}. A polygon is 3 to
 * 200 vertices, stored open: a ring posted closed (last vertex equal to the first) has the
 * repeat dropped. It must enclose an area and must not cross or touch itself. The crossing test
 * runs on the coordinates as integers in millionths, so it is exact.
 */
public final class RegionGeometry {

    public static final int SCALE = DefectPhotoAnnotation.COORDINATE_SCALE;
    public static final int MIN_VERTICES = 3;
    public static final int MAX_VERTICES = 200;

    /** A validated shape: the bounding box, and for a polygon its open vertex list. */
    public record Normalised(RegionShape shape, BigDecimal x1, BigDecimal y1, BigDecimal x2, BigDecimal y2,
                             List<List<BigDecimal>> points) {

        public List<BigDecimal> box() {
            return List.of(x1, y1, x2, y2);
        }
    }

    private RegionGeometry() {
    }

    /**
     * @param where names the region in error messages, for example {@code "regions[2]"}
     * @throws InvalidRequestException if the shape is missing, the wrong fields are set for it,
     *                                 or the geometry is invalid
     */
    public static Normalised normalise(String where, RegionShape shape, List<BigDecimal> box,
                                       List<List<BigDecimal>> points) {
        if (shape == null) {
            throw new InvalidRequestException(where + ": shape is required");
        }
        return switch (shape) {
            case BOX -> {
                if (points != null) {
                    throw new InvalidRequestException(where + ": a box takes box, not points");
                }
                yield box(where, box);
            }
            case POLYGON -> {
                if (box != null) {
                    throw new InvalidRequestException(where + ": a polygon takes points; its box is derived from them");
                }
                yield polygon(where, points);
            }
        };
    }

    static Normalised box(String where, List<BigDecimal> box) {
        if (box == null || box.size() != 4) {
            throw new InvalidRequestException(where + ": box needs exactly four values [x1, y1, x2, y2]");
        }
        BigDecimal x1 = coordinate(where, box.get(0));
        BigDecimal y1 = coordinate(where, box.get(1));
        BigDecimal x2 = coordinate(where, box.get(2));
        BigDecimal y2 = coordinate(where, box.get(3));
        if (x1.compareTo(x2) >= 0 || y1.compareTo(y2) >= 0) {
            throw invalid(where, "box needs x1 < x2 and y1 < y2 at " + SCALE + " decimal places");
        }
        return new Normalised(RegionShape.BOX, x1, y1, x2, y2, null);
    }

    static Normalised polygon(String where, List<List<BigDecimal>> raw) {
        if (raw == null) {
            throw new InvalidRequestException(where + ": a polygon needs points");
        }
        List<List<BigDecimal>> points = new ArrayList<>(raw.size());
        for (List<BigDecimal> vertex : raw) {
            if (vertex == null || vertex.size() != 2) {
                throw new InvalidRequestException(where + ": each polygon vertex is [x, y]");
            }
            points.add(List.of(coordinate(where, vertex.get(0)), coordinate(where, vertex.get(1))));
        }
        if (points.size() > 1 && points.get(0).equals(points.get(points.size() - 1))) {
            points.remove(points.size() - 1);
        }
        if (points.size() < MIN_VERTICES || points.size() > MAX_VERTICES) {
            throw invalid(where, "a polygon needs " + MIN_VERTICES + " to " + MAX_VERTICES
                    + " vertices, got " + points.size());
        }
        int n = points.size();
        long[] xs = new long[n];
        long[] ys = new long[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points.get(i).get(0).unscaledValue().longValueExact();
            ys[i] = points.get(i).get(1).unscaledValue().longValueExact();
        }
        requireSimple(where, xs, ys);
        BigDecimal minX = points.get(0).get(0);
        BigDecimal minY = points.get(0).get(1);
        BigDecimal maxX = minX;
        BigDecimal maxY = minY;
        for (List<BigDecimal> p : points) {
            minX = minX.min(p.get(0));
            minY = minY.min(p.get(1));
            maxX = maxX.max(p.get(0));
            maxY = maxY.max(p.get(1));
        }
        return new Normalised(RegionShape.POLYGON, minX, minY, maxX, maxY, List.copyOf(points));
    }

    /** Rounds to the stored scale and checks the result is in [0, 1]. */
    static BigDecimal coordinate(String where, BigDecimal value) {
        if (value == null) {
            throw new InvalidRequestException(where + ": a coordinate is missing");
        }
        BigDecimal rounded = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (rounded.signum() < 0 || rounded.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(where, "coordinates are fractions of the image in [0, 1], got " + value.toPlainString());
        }
        return rounded;
    }

    /** No repeated vertex, a non-zero area, and no two edges that meet anywhere but their shared vertex. */
    private static void requireSimple(String where, long[] xs, long[] ys) {
        int n = xs.length;
        long twiceArea = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (xs[i] == xs[j] && ys[i] == ys[j]) {
                throw invalid(where, "polygon repeats vertex " + i);
            }
            twiceArea += xs[i] * ys[j] - xs[j] * ys[i];
        }
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            for (int j = i + 1; j < n; j++) {
                int j2 = (j + 1) % n;
                boolean adjacent = j == i + 1 || (i == 0 && j == n - 1);
                if (adjacent) {
                    // Neighbours share one vertex; they cross only by folding back over each other.
                    int shared = j == i + 1 ? i2 : i;
                    int a = shared == i2 ? i : i2;
                    int b = shared == j ? j2 : j;
                    if (orientation(xs, ys, shared, a, b) == 0
                            && (onSegment(xs, ys, shared, a, b) || onSegment(xs, ys, shared, b, a))) {
                        throw invalid(where, "polygon folds back on itself at vertex " + shared);
                    }
                } else if (intersect(xs, ys, i, i2, j, j2)) {
                    throw invalid(where, "polygon edges " + i + " and " + j + " cross");
                }
            }
        }
        // Checked after the crossings: a bow tie's two lobes cancel to zero area too.
        if (twiceArea == 0) {
            throw invalid(where, "polygon encloses no area");
        }
    }

    private static boolean intersect(long[] xs, long[] ys, int p1, int p2, int q1, int q2) {
        int o1 = orientation(xs, ys, p1, p2, q1);
        int o2 = orientation(xs, ys, p1, p2, q2);
        int o3 = orientation(xs, ys, q1, q2, p1);
        int o4 = orientation(xs, ys, q1, q2, p2);
        if (o1 != o2 && o3 != o4) {
            return true;
        }
        return (o1 == 0 && onSegment(xs, ys, p1, q1, p2))
                || (o2 == 0 && onSegment(xs, ys, p1, q2, p2))
                || (o3 == 0 && onSegment(xs, ys, q1, p1, q2))
                || (o4 == 0 && onSegment(xs, ys, q1, p2, q2));
    }

    /** Sign of the cross product (b - a) x (c - a): 0 collinear, 1 counter-clockwise, -1 clockwise. */
    private static int orientation(long[] xs, long[] ys, int a, int b, int c) {
        return Long.signum((xs[b] - xs[a]) * (ys[c] - ys[a]) - (ys[b] - ys[a]) * (xs[c] - xs[a]));
    }

    /** Whether collinear point {@code q} lies on the segment from {@code p} to {@code r}. */
    private static boolean onSegment(long[] xs, long[] ys, int p, int q, int r) {
        return xs[q] >= Math.min(xs[p], xs[r]) && xs[q] <= Math.max(xs[p], xs[r])
                && ys[q] >= Math.min(ys[p], ys[r]) && ys[q] <= Math.max(ys[p], ys[r]);
    }

    /** A stored region's bounding box as {@code [x1, y1, x2, y2]}. */
    public static List<BigDecimal> boxOf(ObservationRegion region) {
        return List.of(region.getX1(), region.getY1(), region.getX2(), region.getY2());
    }

    /** Vertices as text at the stored scale, so a polygon read back from JSON compares equal to one just validated. */
    public static List<String> pointsKey(List<List<BigDecimal>> points) {
        if (points == null) {
            return null;
        }
        return points.stream()
                .map(p -> p.get(0).setScale(SCALE, RoundingMode.HALF_UP).toPlainString() + ","
                        + p.get(1).setScale(SCALE, RoundingMode.HALF_UP).toPlainString())
                .toList();
    }

    private static InvalidRequestException invalid(String where, String message) {
        return new InvalidRequestException(where + ": " + message + " (INVALID_GEOMETRY)");
    }
}
