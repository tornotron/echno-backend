package org.tornotron.echno_backend.modules.documentingestion.extraction.values;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads amounts, percentages, dates and spans of time as tender documents print them: Indian digit
 * grouping, "crore" and "lakh", day-first dates, "Month 4" and "120 days". Values stay as printed on the
 * candidate; these readers run where a typed value is needed, at apply time or for a check, so a
 * reviewer's correction is what gets read.
 */
public final class TenderValues {

    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3}(?:,\\d{2,3})+|\\d+)(\\.\\d+)?");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|per\\s*cent|percent)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_DATE = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4}|\\d{2})");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern NAMED_DATE = Pattern.compile(
            "(\\d{1,2})(?:st|nd|rd|th)?[\\s.-]*(?:of\\s+)?([A-Za-z]{3,9})[\\s.,-]*(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_DATE_US = Pattern.compile(
            "([A-Za-z]{3,9})\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[:.](\\d{2})\\s*(am|pm|a\\.m\\.|p\\.m\\.|hrs|hours)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPAN_AFTER = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:\\(\\s*[A-Za-z ]+\\s*\\)\\s*)?(day|week|month|year)s?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPAN_BEFORE = Pattern.compile("\\b(day|week|month|year)\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);

    private TenderValues() {
    }

    /** A span of time from a start, such as {@code Month 4} or {@code 120 days}. */
    public enum Unit {
        DAY, WEEK, MONTH, YEAR
    }

    /**
     * A span counted from the project's start.
     *
     * @param amount Whole units; a fraction of a month is not counted
     */
    public record Span(int amount, Unit unit) {

        public LocalDate from(LocalDate start) {
            return switch (unit) {
                case DAY -> start.plusDays(amount);
                case WEEK -> start.plusWeeks(amount);
                case MONTH -> start.plusMonths(amount);
                case YEAR -> start.plusYears(amount);
            };
        }

        /** Rough days, only to compare two spans for order. */
        public int approximateDays() {
            return switch (unit) {
                case DAY -> amount;
                case WEEK -> amount * 7;
                case MONTH -> amount * 30;
                case YEAR -> amount * 365;
            };
        }
    }

    /**
     * An amount, in rupees, from text such as {@code Rs. 4,20,00,000/-}, {@code 1.25 crore} or
     * {@code 80 lakh}. Empty when the text holds no number, or holds two that could each be meant.
     */
    public static Optional<BigDecimal> amount(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.replace('\u00a0', ' ');
        Matcher m = NUMBER.matcher(t);
        BigDecimal found = null;
        int end = -1;
        while (m.find()) {
            if (found != null) {
                return Optional.empty();
            }
            found = new BigDecimal(m.group(1).replace(",", "") + (m.group(2) == null ? "" : m.group(2)));
            end = m.end();
        }
        if (found == null) {
            return Optional.empty();
        }
        String after = t.substring(end).toLowerCase(Locale.ROOT).stripLeading();
        if (after.startsWith("crore") || after.startsWith("cr")) {
            found = found.multiply(BigDecimal.valueOf(10_000_000L));
        } else if (after.startsWith("lakh") || after.startsWith("lac")) {
            found = found.multiply(BigDecimal.valueOf(100_000L));
        }
        return Optional.of(found.stripTrailingZeros().scale() < 0 ? found.setScale(0) : found);
    }

    /** A percentage from text such as {@code 20 %} or {@code 1.25% of the tendered value}. */
    public static Optional<BigDecimal> percent(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = PERCENT.matcher(text);
        return m.find() ? Optional.of(new BigDecimal(m.group(1))) : Optional.empty();
    }

    /** A plain number such as {@code 20} or {@code 20.5}, or a percentage; for a percent column. */
    public static Optional<BigDecimal> percentOrNumber(String text) {
        Optional<BigDecimal> p = percent(text);
        if (p.isPresent() || text == null) {
            return p;
        }
        String t = text.strip();
        return t.matches("\\d+(\\.\\d+)?") ? Optional.of(new BigDecimal(t)) : Optional.empty();
    }

    /** A date, day first, from {@code 25.11.2026}, {@code 25 Nov 2026}, {@code November 25, 2026} or ISO. */
    public static Optional<LocalDate> date(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher iso = ISO_DATE.matcher(text);
        if (iso.find()) {
            return safe(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        }
        Matcher n = NUMERIC_DATE.matcher(text);
        if (n.find()) {
            int year = Integer.parseInt(n.group(3));
            return safe(year < 100 ? 2000 + year : year, Integer.parseInt(n.group(2)), Integer.parseInt(n.group(1)));
        }
        Matcher named = NAMED_DATE.matcher(text);
        if (named.find()) {
            Optional<Month> month = month(named.group(2));
            if (month.isPresent()) {
                return safe(Integer.parseInt(named.group(3)), month.get().getValue(), Integer.parseInt(named.group(1)));
            }
        }
        Matcher us = NAMED_DATE_US.matcher(text);
        if (us.find()) {
            Optional<Month> month = month(us.group(1));
            if (month.isPresent()) {
                return safe(Integer.parseInt(us.group(3)), month.get().getValue(), Integer.parseInt(us.group(2)));
            }
        }
        return Optional.empty();
    }

    /** A date and, when printed, a time of day; a date alone reads as the end of that day. */
    public static Optional<LocalDateTime> dateTime(String text) {
        Optional<LocalDate> day = date(text);
        if (day.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = TIME.matcher(text.replaceAll("\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}", " "));
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = Integer.parseInt(m.group(2));
            String suffix = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
            if (suffix.startsWith("p") && hour < 12) {
                hour += 12;
            } else if (suffix.startsWith("a") && hour == 12) {
                hour = 0;
            }
            if (hour < 24 && minute < 60) {
                return Optional.of(day.get().atTime(hour, minute));
            }
        }
        return Optional.of(day.get().atTime(LocalTime.of(23, 59)));
    }

    /**
     * A span from the start, from {@code Month 4}, {@code 4 months}, {@code 120 (one hundred twenty) days}
     * or {@code within 90 days of the start}. Empty for text that is a date or holds no span.
     */
    public static Optional<Span> span(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher before = SPAN_BEFORE.matcher(text);
        if (before.find()) {
            return Optional.of(new Span(Integer.parseInt(before.group(2)), unit(before.group(1))));
        }
        Matcher after = SPAN_AFTER.matcher(text);
        if (after.find()) {
            BigDecimal amount = new BigDecimal(after.group(1));
            if (amount.stripTrailingZeros().scale() > 0) {
                return Optional.empty();
            }
            return Optional.of(new Span(amount.intValueExact(), unit(after.group(2))));
        }
        return Optional.empty();
    }

    /** Days, from a span or a bare number of days, for a period such as bid validity. */
    public static Optional<Integer> days(String text) {
        return span(text).map(s -> s.unit() == Unit.DAY ? s.amount() : s.approximateDays());
    }

    private static Unit unit(String raw) {
        return Unit.valueOf(raw.toUpperCase(Locale.ROOT));
    }

    private static Optional<Month> month(String raw) {
        String r = raw.toLowerCase(Locale.ROOT);
        for (Month m : Month.values()) {
            String full = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (r.length() >= 3 && (full.equals(r) || full.startsWith(r) && r.length() <= 4)) {
                return Optional.of(m);
            }
        }
        if (r.equals("sept")) {
            return Optional.of(Month.SEPTEMBER);
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> safe(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }
}
