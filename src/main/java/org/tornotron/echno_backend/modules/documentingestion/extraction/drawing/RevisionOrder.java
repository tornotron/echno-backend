package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How two revisions of one drawing number are ordered, and how a number or a revision is compared.
 *
 * <p>Revisions of the same shape are ordered by their own sequence: letters as {@code A < B < ... < Z <
 * AA}, numbers numerically ({@code 00 < 01 < 2}), and a letter followed by a number ({@code P1}, {@code C2},
 * {@code R3}) by the number when the letters match. Two revisions of different shapes, such as {@code P3}
 * and {@code C1}, follow no rule a reader could check, so they are ordered by their dates when both have
 * one, and otherwise by which came later. No revision at all is the oldest.
 */
public final class RevisionOrder {

    private static final Pattern LETTERS = Pattern.compile("[A-Z]{1,3}");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,4}");
    private static final Pattern PREFIXED = Pattern.compile("([A-Z]{1,2})(\\d{1,4})");
    private static final Pattern REV_WORD = Pattern.compile("^(REVISION|REV)[\\s.:-]*");

    private RevisionOrder() {
    }

    /** The number as compared: upper case, with spaces removed. {@code RCB-AR-101} and {@code rcb-ar-101} match. */
    public static String numberKey(String number) {
        return number == null ? "" : number.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** The revision as compared: upper case, no spaces, and no leading "Rev". {@code Rev. B} is {@code B}. */
    public static String revisionKey(String revision) {
        if (revision == null) {
            return "";
        }
        String r = revision.strip().toUpperCase(Locale.ROOT);
        Matcher m = REV_WORD.matcher(r);
        if (m.find() && m.end() < r.length()) {
            r = r.substring(m.end());
        }
        return r.replaceAll("\\s+", "");
    }

    /**
     * Compares two revisions by their sequence alone.
     *
     * @return negative, zero or positive as {@code a} is older than, level with, or newer than {@code b}, or
     *         empty when their shapes differ and the sequence cannot say
     */
    public static Optional<Integer> bySequence(String a, String b) {
        String x = revisionKey(a);
        String y = revisionKey(b);
        if (x.isEmpty() || y.isEmpty()) {
            return Optional.of(Boolean.compare(!x.isEmpty(), !y.isEmpty()));
        }
        if (DIGITS.matcher(x).matches() && DIGITS.matcher(y).matches()) {
            return Optional.of(Integer.compare(Integer.parseInt(x), Integer.parseInt(y)));
        }
        if (LETTERS.matcher(x).matches() && LETTERS.matcher(y).matches()) {
            return Optional.of(Integer.compare(letters(x), letters(y)));
        }
        Matcher mx = PREFIXED.matcher(x);
        Matcher my = PREFIXED.matcher(y);
        if (mx.matches() && my.matches() && mx.group(1).equals(my.group(1))) {
            return Optional.of(Integer.compare(Integer.parseInt(mx.group(2)), Integer.parseInt(my.group(2))));
        }
        return Optional.empty();
    }

    /**
     * Compares two revisions: by sequence, then by date, then by the order they arrived in.
     *
     * @param arrivalA A value that orders by arrival, such as when each was applied
     */
    public static <T extends Comparable<T>> int compare(String a, LocalDate dateA, T arrivalA,
                                                        String b, LocalDate dateB, T arrivalB) {
        Optional<Integer> sequence = bySequence(a, b);
        if (sequence.isPresent() && sequence.get() != 0) {
            return sequence.get();
        }
        if (dateA != null && dateB != null && !dateA.equals(dateB)) {
            return dateA.compareTo(dateB);
        }
        if (arrivalA == null || arrivalB == null) {
            return 0;
        }
        return arrivalA.compareTo(arrivalB);
    }

    private static int letters(String s) {
        int v = 0;
        for (char c : s.toCharArray()) {
            v = v * 26 + (c - 'A' + 1);
        }
        return v;
    }
}
