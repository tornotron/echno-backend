package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The payload of a {@code DRAWING} candidate: the title-block fields as printed, under the names the
 * corpus labels use, and where the sheet came from. The natural key is the drawing number.
 */
public final class DrawingFields {

    public static final String NUMBER = "drawing_number";
    public static final String TITLE = "title";
    public static final String DISCIPLINE = "discipline";
    public static final String REVISION = "revision";
    public static final String DATE = "date";
    public static final String SCALE = "scale";
    /** The printed revision history: a list of {@code {revision, date, description}}, oldest first. */
    public static final String HISTORY = "revision_history";
    /** The page of the PDF the sheet is on, or null for a DXF or DWG. */
    public static final String SHEET_PAGE = "sheet_page";
    /** The DXF layout the title block sits in, or null. */
    public static final String LAYOUT = "layout";
    /** The crop's key relative to the document's output prefix, or null when the worker cropped nothing. */
    public static final String CROP_IMAGE = "crop_image";
    /** {@code PDF}, {@code DXF} or {@code DWG}. */
    public static final String SOURCE_FORMAT = "source_format";

    public static final String HISTORY_REVISION = "revision";
    public static final String HISTORY_DATE = "date";
    public static final String HISTORY_DESCRIPTION = "description";

    /** The value fields, in the order the review screen shows them. */
    public static final List<String> VALUES = List.of(NUMBER, TITLE, DISCIPLINE, REVISION, DATE, SCALE);

    // Flags.
    public static final String NOT_READ = "NOT_READ";
    public static final String NOT_READ_MESSAGE = "Not read, add a PDF or DXF copy.";
    public static final String NUMBER_FROM_FILE_NAME = "NUMBER_FROM_FILE_NAME";
    public static final String NO_REVISION = "NO_REVISION";

    static final int MAX_NUMBER_LENGTH = 60;
    static final int MAX_REVISION_LENGTH = 12;

    private static final Pattern FILE_REVISION = Pattern.compile("(?i)^(.+?)[\\s_.-]+rev(?:ision)?[\\s_.-]*([A-Z0-9]{1,3})$");

    /**
     * Two-letter and longer discipline codes as they appear in a drawing number, such as {@code AR} in
     * {@code RCB-AR-101}. Single letters are left out: {@code C} or {@code E} is as often a block or a
     * phase as a discipline.
     */
    private static final Map<String, String> DISCIPLINE_CODES = codes();

    private DrawingFields() {
    }

    /** The discipline a number's code names, from any part after the first, or empty. */
    public static Optional<String> disciplineFromNumber(String number) {
        if (number == null) {
            return Optional.empty();
        }
        String[] parts = number.toUpperCase(Locale.ROOT).split("[-_/.\\s]+");
        for (int i = 1; i < parts.length; i++) {
            String d = DISCIPLINE_CODES.get(parts[i]);
            if (d != null) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /**
     * The number and revision a file name gives: the name without its extension, and a trailing
     * {@code Rev B} or {@code _revB} taken as the revision.
     *
     * @return the number, and the revision or null
     */
    public static String[] fromFileName(String fileName) {
        String stem = fileName == null ? "" : fileName.strip();
        int slash = Math.max(stem.lastIndexOf('/'), stem.lastIndexOf('\\'));
        stem = stem.substring(slash + 1);
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        Matcher m = FILE_REVISION.matcher(stem);
        if (m.matches()) {
            return new String[] {cut(m.group(1).strip(), MAX_NUMBER_LENGTH), m.group(2).toUpperCase(Locale.ROOT)};
        }
        return new String[] {cut(stem.isEmpty() ? "drawing" : stem, MAX_NUMBER_LENGTH), null};
    }

    /** Whether text reads as a drawing number: one short code with a digit, such as {@code RCB-AR-101}. */
    public static boolean looksLikeNumber(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        return t.length() >= 3 && t.length() <= MAX_NUMBER_LENGTH && t.chars().anyMatch(Character::isDigit)
                && t.chars().anyMatch(Character::isLetterOrDigit) && !t.contains(":");
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Map<String, String> codes() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : List.of("AR", "ARC", "ARCH")) {
            m.put(c, "Architectural");
        }
        for (String c : List.of("ST", "STR", "STRUCT", "SE")) {
            m.put(c, "Structural");
        }
        for (String c : List.of("ME", "MECH")) {
            m.put(c, "Mechanical");
        }
        for (String c : List.of("EL", "ELE", "ELEC")) {
            m.put(c, "Electrical");
        }
        for (String c : List.of("PL", "PLB", "PH")) {
            m.put(c, "Plumbing");
        }
        for (String c : List.of("CV", "CIV", "CE")) {
            m.put(c, "Civil");
        }
        for (String c : List.of("HV", "HVAC", "AC")) {
            m.put(c, "HVAC");
        }
        for (String c : List.of("FP", "FF", "FS")) {
            m.put(c, "Fire protection");
        }
        for (String c : List.of("LS", "LA", "LND")) {
            m.put(c, "Landscape");
        }
        for (String c : List.of("ID", "INT")) {
            m.put(c, "Interior");
        }
        return Map.copyOf(m);
    }
}
