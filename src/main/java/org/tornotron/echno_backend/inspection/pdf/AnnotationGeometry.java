package org.tornotron.echno_backend.inspection.pdf;

import org.tornotron.echno_backend.inspection.DefectAnnotationShape;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationDto;
import org.tornotron.echno_backend.pdfGeneration.ReportText;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the stored annotation geometry into the percentages a report places a mark
 * with.
 *
 * <p>Kept out of the report service so it can be tested as what it is, a pure
 * function from four numbers to four CSS lengths, without starting a renderer.
 *
 * <p>Two decisions live here:
 *
 * <ul>
 *   <li>A box shape is normalized. The two stored points are corners in whichever
 *       order they were drawn, so a mark dragged up and to the left has a larger
 *       first point than second. Placing it needs the smaller corner and a positive
 *       extent, which is what {@code min} and {@code abs} produce.</li>
 *   <li>An arrow is drawn as a pin on its head rather than as a line. This build of
 *       openhtmltopdf has no SVG support, and a rotated line would rest on transform
 *       behaviour that a PDF renderer is a poor place to depend on. What an arrow
 *       says is where it lands, and a pin says that without either. Nothing is lost
 *       from the record: the stored geometry keeps both points, so a browser drawing
 *       the same annotation renders the full arrow.</li>
 * </ul>
 */
public final class AnnotationGeometry {

    /** Diameter of an arrow's pin, as a percentage of the plate. */
    public static final BigDecimal PIN_SIZE = new BigDecimal("3.2");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int PLACES = 2;

    private AnnotationGeometry() {
    }

    /**
     * Places a photo's marks, numbering them in the order they are given.
     *
     * @param marks The marks on one photo, already in print order.
     * @return One placed mark per input, in the same order.
     */
    public static List<PlacedMark> place(List<DefectPhotoAnnotationDto> marks) {
        List<PlacedMark> placed = new ArrayList<>(marks.size());
        int index = 0;
        for (DefectPhotoAnnotationDto mark : marks) {
            index++;
            placed.add(place(index, mark));
        }
        return placed;
    }

    private static PlacedMark place(int index, DefectPhotoAnnotationDto mark) {
        String label = ReportText.orDash(mark.label());

        if (mark.shape() == DefectAnnotationShape.ARROW) {
            BigDecimal half = PIN_SIZE.divide(BigDecimal.TWO, PLACES + 2, RoundingMode.HALF_UP);
            return new PlacedMark(
                    index,
                    "pin",
                    percent(mark.x2().multiply(HUNDRED).subtract(half)),
                    percent(mark.y2().multiply(HUNDRED).subtract(half)),
                    PIN_SIZE.toPlainString(),
                    PIN_SIZE.toPlainString(),
                    label);
        }

        BigDecimal left = mark.x1().min(mark.x2()).multiply(HUNDRED);
        BigDecimal top = mark.y1().min(mark.y2()).multiply(HUNDRED);
        BigDecimal width = mark.x1().subtract(mark.x2()).abs().multiply(HUNDRED);
        BigDecimal height = mark.y1().subtract(mark.y2()).abs().multiply(HUNDRED);

        return new PlacedMark(
                index,
                mark.shape() == DefectAnnotationShape.ELLIPSE ? "ellipse" : "box",
                percent(left),
                percent(top),
                percent(width),
                percent(height),
                label);
    }

    private static String percent(BigDecimal value) {
        return value.setScale(PLACES, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * One mark, converted to the percentages the template positions it with.
     *
     * @param index The mark's number on its plate, printed in its tag and its legend.
     * @param kind  The CSS class the template appends: box, ellipse or pin.
     */
    public record PlacedMark(int index,
                             String kind,
                             String left,
                             String top,
                             String width,
                             String height,
                             String label) {
    }
}
