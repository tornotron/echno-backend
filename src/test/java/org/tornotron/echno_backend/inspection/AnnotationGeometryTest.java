package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationDto;
import org.tornotron.echno_backend.inspection.pdf.AnnotationGeometry;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placing a mark over a photograph. A pure function from four stored fractions to
 * four CSS lengths, so this needs no Spring context and no renderer: the test JVM
 * is capped at 1 GB with every cached context living for the whole run, and a
 * context this needs nothing from is a context it must not start.
 */
class AnnotationGeometryTest {

    @Test
    void placesABoxAtItsSmallerCornerWithAPositiveExtent() {
        List<AnnotationGeometry.PlacedMark> placed = AnnotationGeometry.place(List.of(
                mark(DefectAnnotationShape.RECTANGLE, "0.20", "0.30", "0.50", "0.70")));

        AnnotationGeometry.PlacedMark box = placed.getFirst();
        assertThat(box.kind()).isEqualTo("box");
        assertThat(box.left()).isEqualTo("20.00");
        assertThat(box.top()).isEqualTo("30.00");
        assertThat(box.width()).isEqualTo("30.00");
        assertThat(box.height()).isEqualTo("40.00");
    }

    /**
     * The two points are corners in whichever order the mark was drawn, so one
     * dragged up and to the left arrives with its first point larger than its
     * second. Placing that without normalizing gives a negative width, which CSS
     * discards, and the mark vanishes from the report while the record still says
     * it is there.
     */
    @Test
    void normalizesABoxDrawnFromItsFarCornerBack() {
        List<AnnotationGeometry.PlacedMark> placed = AnnotationGeometry.place(List.of(
                mark(DefectAnnotationShape.RECTANGLE, "0.50", "0.70", "0.20", "0.30")));

        AnnotationGeometry.PlacedMark box = placed.getFirst();
        assertThat(box.left()).isEqualTo("20.00");
        assertThat(box.top()).isEqualTo("30.00");
        assertThat(box.width()).isEqualTo("30.00");
        assertThat(box.height()).isEqualTo("40.00");
    }

    @Test
    void anEllipseIsPlacedLikeABoxAndKeepsItsOwnKind() {
        List<AnnotationGeometry.PlacedMark> placed = AnnotationGeometry.place(List.of(
                mark(DefectAnnotationShape.ELLIPSE, "0.10", "0.10", "0.40", "0.60")));

        AnnotationGeometry.PlacedMark ellipse = placed.getFirst();
        assertThat(ellipse.kind()).isEqualTo("ellipse");
        assertThat(ellipse.left()).isEqualTo("10.00");
        assertThat(ellipse.width()).isEqualTo("30.00");
    }

    /**
     * An arrow becomes a pin centred on its head, not on its tail and not on the
     * box between them. The head is the point the annotation is making, and a pin
     * placed on the tail marks the empty wall the arrow was drawn from.
     */
    @Test
    void placesAnArrowAsAPinCentredOnItsHead() {
        List<AnnotationGeometry.PlacedMark> placed = AnnotationGeometry.place(List.of(
                mark(DefectAnnotationShape.ARROW, "0.10", "0.10", "0.60", "0.80")));

        AnnotationGeometry.PlacedMark pin = placed.getFirst();
        assertThat(pin.kind()).isEqualTo("pin");
        assertThat(pin.width()).isEqualTo(AnnotationGeometry.PIN_SIZE.toPlainString());
        assertThat(pin.height()).isEqualTo(AnnotationGeometry.PIN_SIZE.toPlainString());
        // 60% and 80% less half the pin, so the pin's centre lands on the head
        assertThat(new BigDecimal(pin.left())).isEqualByComparingTo("58.40");
        assertThat(new BigDecimal(pin.top())).isEqualByComparingTo("78.40");
    }

    @Test
    void numbersTheMarksInTheOrderTheyAreGiven() {
        List<AnnotationGeometry.PlacedMark> placed = AnnotationGeometry.place(List.of(
                mark(DefectAnnotationShape.RECTANGLE, "0.10", "0.10", "0.20", "0.20"),
                mark(DefectAnnotationShape.ARROW, "0.30", "0.30", "0.40", "0.40"),
                mark(DefectAnnotationShape.ELLIPSE, "0.50", "0.50", "0.60", "0.60")));

        assertThat(placed).extracting(AnnotationGeometry.PlacedMark::index)
                .containsExactly(1, 2, 3);
    }

    @Test
    void marksWithNoLabelPrintThePlaceholderRatherThanNothing() {
        List<AnnotationGeometry.PlacedMark> placed = AnnotationGeometry.place(List.of(
                new DefectPhotoAnnotationDto(UUID.randomUUID(), UUID.randomUUID(), "photo.jpg",
                        DefectAnnotationShape.RECTANGLE, new BigDecimal("0.1"),
                        new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.2"),
                        "  ", 0, null)));

        assertThat(placed.getFirst().label()).isNotBlank();
    }

    private static DefectPhotoAnnotationDto mark(DefectAnnotationShape shape,
                                                 String x1, String y1, String x2, String y2) {
        return new DefectPhotoAnnotationDto(
                UUID.randomUUID(), UUID.randomUUID(), "photo.jpg", shape,
                new BigDecimal(x1), new BigDecimal(y1), new BigDecimal(x2), new BigDecimal(y2),
                "Honeycombing", 0, 7L);
    }
}
