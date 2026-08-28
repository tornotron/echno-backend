package org.tornotron.echno_backend.pdfGeneration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Display formatting shared by the PDF reports, so a date, an enum label and an
 * empty field look the same on every document Echno produces.
 *
 * <p>Formatting belongs here rather than in a template. A Thymeleaf expression
 * that formats is untestable, silently null-tolerant in the wrong direction, and
 * has to be repeated per template; a static method is none of those. The reports
 * put display-ready strings into the context and the templates only place them.
 */
public final class ReportText {

    /** What an empty field prints as, so a blank cell is deliberate rather than missing. */
    public static final String DASH = "—";

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    private ReportText() {
    }

    /** A date, or the placeholder when there is none. */
    public static String date(LocalDate value) {
        return value == null ? DASH : DATE_FMT.format(value);
    }

    /** A date and time, or the placeholder when there is none. */
    public static String stamp(LocalDateTime value) {
        return value == null ? DASH : STAMP_FMT.format(value);
    }

    /** The moment the document was produced, for its footer. */
    public static String generatedNow() {
        return STAMP_FMT.format(LocalDateTime.now());
    }

    /** An enum constant as a reader would write it: {@code PARTIALLY_PAID} to {@code Partially Paid}. */
    public static String humanise(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return DASH;
        }
        String[] parts = enumName.toLowerCase(Locale.ENGLISH).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** An enum as a reader would write it, or the placeholder when there is none. */
    public static String humanise(Enum<?> value) {
        return value == null ? DASH : humanise(value.name());
    }

    /** Text, or the placeholder when it is absent or blank. */
    public static String orDash(String value) {
        return value == null || value.isBlank() ? DASH : value;
    }

    /** Text, or the empty string, for the places a placeholder would read as content. */
    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
