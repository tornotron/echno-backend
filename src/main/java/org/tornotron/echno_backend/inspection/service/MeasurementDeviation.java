package org.tornotron.echno_backend.inspection.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a check point's deviation, the measured value minus the expected one.
 *
 * <p>Both values are free text on site ("148 mm", "40mm", "1:1.5"), because a
 * check point may be dimensional, qualitative or a ratio, and the inspector types
 * whatever the drawing uses. So the deviation is computed only where it is
 * meaningful and left null everywhere else, rather than guessed:
 *
 * <ul>
 *   <li>Both values must parse as a leading decimal number, optionally signed.</li>
 *   <li>Whatever follows the number is the unit. The two units must match, ignoring
 *       case and surrounding spaces. "148 mm" against "150mm" is a deviation of
 *       -2; "15 cm" against "150 mm" is not, because subtracting them would produce
 *       a number in no unit at all and a reader would take it for millimetres.</li>
 *   <li>Anything else, including a missing value, yields null: not recorded, rather
 *       than zero, which would read as "measured exactly on target".</li>
 * </ul>
 *
 * <p>The result is rounded to the four decimal places the {@code deviation} column
 * holds, so a value written with more precision than the column can store is
 * rounded here rather than rejected by the database on insert.
 */
public final class MeasurementDeviation {

    /** Decimal places the {@code inspection_check_items.deviation} column holds. */
    private static final int SCALE = 4;

    /** Leading optional sign and decimal number, then everything else as the unit. */
    private static final Pattern VALUE = Pattern.compile("^([+-]?\\d+(?:\\.\\d+)?)\\s*(.*)$");

    private MeasurementDeviation() {
    }

    /**
     * The deviation of a measurement from its expected value.
     *
     * @param measurement   What was recorded on site, may be null or blank.
     * @param expectedValue What the specification asks for, may be null or blank.
     * @return measurement minus expectedValue, or null when the pair is not a
     *         comparable numeric quantity in one unit.
     */
    public static BigDecimal of(String measurement, String expectedValue) {
        Matcher measured = match(measurement);
        Matcher expected = match(expectedValue);
        if (measured == null || expected == null) {
            return null;
        }
        if (!measured.group(2).trim().equalsIgnoreCase(expected.group(2).trim())) {
            return null;
        }
        return new BigDecimal(measured.group(1))
                .subtract(new BigDecimal(expected.group(1)))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static Matcher match(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = VALUE.matcher(value.trim());
        return matcher.matches() ? matcher : null;
    }
}
