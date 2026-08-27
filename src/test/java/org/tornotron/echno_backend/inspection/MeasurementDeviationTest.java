package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tornotron.echno_backend.inspection.service.MeasurementDeviation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deviation a check point records is the measured value minus the expected
 * one, and both arrive as free text an inspector typed. These cases pin down where
 * that subtraction is meaningful and where the answer has to be "not recorded",
 * which is the distinction that matters: a wrong number here reads as a real
 * measurement on a QA report and a null reads as an unanswered check point.
 *
 * <p>Plain JUnit with no Spring context: the rule is arithmetic and string parsing,
 * and the test JVM is capped at 1 GB with every cached context living for the whole
 * run, so a context this needs nothing from is a context it must not start.
 */
class MeasurementDeviationTest {

    @ParameterizedTest
    @CsvSource({
            // same unit, spacing and case are irrelevant
            "148mm,     150mm,     -2",
            "'148 mm',  150mm,     -2",
            "150MM,     '150 mm',   0",
            "'40 mm',   '35 mm',    5",
            // decimals keep their precision
            "148.25mm,  150mm,     -1.75",
            // an explicit sign parses
            "+2.5mm,    -2.5mm,     5",
            // a bare number has an empty unit on both sides, which still matches
            "12,        10,         2",
    })
    void computesTheDifferenceWhenBothValuesAreNumericAndInOneUnit(String measurement,
                                                                   String expected,
                                                                   String deviation) {
        assertThat(MeasurementDeviation.of(measurement, expected)).isEqualByComparingTo(deviation);
    }

    @ParameterizedTest
    @CsvSource({
            // differing units: subtracting them would produce a number in no unit at all,
            // and a reader would take the answer for millimetres
            "'15 cm',   '150 mm'",
            "150mm,     150",
            // qualitative check points, which is most of a finishing or documentation checklist
            "Level,     Level",
            "'No leak', 'No leak'",
            // a ratio is not a quantity this can subtract
            "1:1.5,     1:2",
            // the number has to lead: a value that starts with text is not measured
            "'approx 150mm', 150mm",
    })
    void returnsNullWhenThePairIsNotAComparableQuantity(String measurement, String expected) {
        assertThat(MeasurementDeviation.of(measurement, expected)).isNull();
    }

    @Test
    void returnsNullWhenEitherValueIsMissing() {
        assertThat(MeasurementDeviation.of(null, "150mm")).isNull();
        assertThat(MeasurementDeviation.of("150mm", null)).isNull();
        assertThat(MeasurementDeviation.of("  ", "150mm")).isNull();
        assertThat(MeasurementDeviation.of(null, null)).isNull();
    }

    @Test
    void roundsToTheScaleTheColumnHolds() {
        // five decimal places in, four out, so the insert cannot be rejected for precision
        assertThat(MeasurementDeviation.of("150.00005mm", "150mm").scale()).isEqualTo(4);
        assertThat(MeasurementDeviation.of("150.00005mm", "150mm")).isEqualByComparingTo("0.0001");
    }
}
