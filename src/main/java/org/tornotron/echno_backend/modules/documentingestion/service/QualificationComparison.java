package org.tornotron.echno_backend.modules.documentingestion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyProfile;
import org.tornotron.echno_backend.modules.documentingestion.domain.MatrixStatus;
import org.tornotron.echno_backend.modules.documentingestion.extraction.qualification.QualificationRequirementType;
import org.tornotron.echno_backend.modules.documentingestion.extraction.values.TenderValues;

/**
 * Compares one qualification requirement, as accepted, with the company profile (design section 4.7).
 * Turnover, similar projects and experience are compared here: amounts, counts, values and years.
 * Equipment and personnel are text, so their status is the one a person set. A blank profile field,
 * or a value that does not read as a number, gives {@code UNKNOWN}; a {@code GAP} is shown only when
 * the figures entered cannot meet the requirement even if every blank were filled in its favour.
 */
public final class QualificationComparison {

    /** Why a row has its status. */
    public enum Basis {
        /** Compared from the figures. */
        COMPARED,
        /** Set by a person, for a requirement in text. */
        SET_BY_PERSON,
        /** A requirement in text whose status no person has set yet. */
        AWAITING_PERSON,
        /** The company profile field is blank. */
        PROFILE_BLANK,
        /** The tender's value does not read as a number, so a person compares it. */
        NOT_READABLE
    }

    /**
     * @param companyValue What the profile says, in words, or null when the field is blank
     */
    public record Outcome(String companyValue, MatrixStatus status, Basis basis, String reason) {
    }

    private static final BigDecimal CRORE = BigDecimal.valueOf(10_000_000L);
    private static final BigDecimal LAKH = BigDecimal.valueOf(100_000L);
    private static final Pattern DIGITS = Pattern.compile("(?<![\\d.])(\\d{1,4})(?![\\d.,]\\d)");
    private static final Pattern WORD = Pattern.compile("\\b([a-z]+)\\b");
    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14),
            Map.entry("fifteen", 15), Map.entry("twenty", 20), Map.entry("twenty-five", 25));
    private static final int SHOWN_ITEMS = 5;
    /** A period met in every year counted, so the lowest year is compared. */
    private static final Pattern EACH_YEAR = Pattern.compile("\\b(each|every)\\b");
    /** A period met in any one year counted, so the highest year is compared. */
    private static final Pattern ANY_YEAR = Pattern.compile("\\b(any|one of|best|highest)\\b");

    private QualificationComparison() {
    }

    /** The requirement as the tender states it, in one line. */
    public static String tenderValue(QualificationRequirementType type, String value, String minValue, String period) {
        StringBuilder sb = new StringBuilder(value);
        if (type.hasMinValue() && minValue != null) {
            sb.append(", each at least ").append(minValue);
        }
        if (type.hasPeriod() && period != null) {
            sb.append(" (").append(period).append(')');
        }
        return sb.toString();
    }

    /**
     * @param profile     The company profile, or null when none was entered
     * @param personStatus The status a person set, read only for a requirement in text
     * @param currentYear The year a period of years counts back from
     */
    public static Outcome compare(QualificationRequirementType type, String value, String minValue, String period,
                                  CompanyProfile profile, MatrixStatus personStatus, int currentYear) {
        return switch (type) {
            case TURNOVER -> turnover(value, period, profile == null ? List.of() : profile.getTurnover());
            case SIMILAR_PROJECTS -> similarProjects(value, minValue, period,
                    profile == null ? List.of() : profile.getSimilarProjects(), currentYear);
            case EXPERIENCE -> experience(value, profile == null ? null : profile.getYearsInBusiness());
            case EQUIPMENT -> byPerson(type, profile == null ? List.of() : profile.getEquipment().stream()
                    .map(e -> e.quantity() == null ? e.name() : e.name() + " (" + e.quantity() + ")").toList(),
                    "item", "items", "lists no equipment", personStatus);
            case PERSONNEL -> byPerson(type, profile == null ? List.of() : profile.getPersonnel().stream()
                    .map(CompanyProfile.PersonnelEntry::role).toList(), "person", "people", "lists no personnel",
                    personStatus);
        };
    }

    static Outcome turnover(String value, String period, List<CompanyProfile.TurnoverYear> years) {
        Optional<BigDecimal> required = TenderValues.amount(value);
        if (required.isEmpty()) {
            return unreadable("The turnover \"" + value + "\" does not read as an amount in rupees.");
        }
        if (years == null || years.isEmpty()) {
            return blank("The company profile has no turnover entered.");
        }
        List<CompanyProfile.TurnoverYear> latestFirst = years.stream()
                .sorted(Comparator.comparingInt((CompanyProfile.TurnoverYear y) -> startYear(y.financialYear())).reversed())
                .toList();
        Integer n = period == null ? null : wholeNumber(period).orElse(null);
        if (n == null || n <= 1) {
            CompanyProfile.TurnoverYear latest = latestFirst.get(0);
            String company = rupees(latest.amount()) + " (" + latest.financialYear() + ")";
            String reason = period != null && n == null
                    ? "The period names no number of years, so the latest year entered is compared."
                    : "The latest year entered is compared.";
            return compared(company, latest.amount().compareTo(required.get()) >= 0, reason);
        }
        if (latestFirst.size() < n) {
            return blank("The tender counts " + n + " years; the company profile has turnover for "
                    + latestFirst.size() + ".");
        }
        List<CompanyProfile.TurnoverYear> window = latestFirst.subList(0, n);
        String span = window.get(n - 1).financialYear() + " to " + window.get(0).financialYear();
        String words = period.toLowerCase(Locale.ROOT);
        BigDecimal company;
        String how;
        if (EACH_YEAR.matcher(words).find()) {
            company = window.stream().map(CompanyProfile.TurnoverYear::amount).min(Comparator.naturalOrder()).orElseThrow();
            how = "lowest";
        } else if (ANY_YEAR.matcher(words).find()) {
            company = window.stream().map(CompanyProfile.TurnoverYear::amount).max(Comparator.naturalOrder()).orElseThrow();
            how = "highest";
        } else {
            company = window.stream().map(CompanyProfile.TurnoverYear::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            how = "average";
        }
        return compared(how + " " + rupees(company) + " over " + span, company.compareTo(required.get()) >= 0,
                "The " + how + " of the latest " + n + " years entered is compared.");
    }

    static Outcome similarProjects(String value, String minValue, String period,
                                   List<CompanyProfile.SimilarProject> projects, int currentYear) {
        Optional<Integer> count = wholeNumber(value);
        if (count.isEmpty()) {
            return unreadable("The number of works \"" + value + "\" does not read as a number.");
        }
        BigDecimal min = null;
        if (minValue != null) {
            Optional<BigDecimal> m = TenderValues.amount(minValue);
            if (m.isEmpty()) {
                return unreadable("The value each work must reach, \"" + minValue + "\", is not an amount in rupees.");
            }
            min = m.get();
        }
        Integer years = null;
        if (period != null) {
            Optional<Integer> y = wholeNumber(period);
            if (y.isEmpty()) {
                return unreadable("The period \"" + period + "\" names no number of years.");
            }
            years = y.get();
        }
        if (projects == null || projects.isEmpty()) {
            return blank("The company profile has no similar projects entered.");
        }
        int qualifying = 0;
        int possible = 0;
        BigDecimal largest = null;
        for (CompanyProfile.SimilarProject p : projects) {
            Boolean valueOk = min == null ? Boolean.TRUE : p.value() == null ? null : p.value().compareTo(min) >= 0;
            Boolean yearOk = years == null ? Boolean.TRUE
                    : p.completedYear() == null ? null : p.completedYear() >= currentYear - years;
            if (Boolean.TRUE.equals(valueOk) && Boolean.TRUE.equals(yearOk)) {
                qualifying++;
            }
            if (!Boolean.FALSE.equals(valueOk) && !Boolean.FALSE.equals(yearOk)) {
                possible++;
            }
            if (p.value() != null && (largest == null || p.value().compareTo(largest) > 0)) {
                largest = p.value();
            }
        }
        String company = qualifying + " qualifying of " + projects.size() + " listed"
                + (largest == null ? "" : ", largest " + rupees(largest));
        if (qualifying >= count.get()) {
            return new Outcome(company, MatrixStatus.MATCH, Basis.COMPARED, null);
        }
        if (possible >= count.get()) {
            return new Outcome(company, MatrixStatus.UNKNOWN, Basis.PROFILE_BLANK, (possible - qualifying)
                    + " more could qualify but have no value or completion year entered.");
        }
        return new Outcome(company, MatrixStatus.GAP, Basis.COMPARED, null);
    }

    static Outcome experience(String value, Integer yearsInBusiness) {
        Optional<Integer> required = wholeNumber(value);
        if (required.isEmpty()) {
            return unreadable("The experience \"" + value + "\" does not read as a number of years.");
        }
        if (yearsInBusiness == null) {
            return blank("The company profile has no years in business entered.");
        }
        return compared(yearsInBusiness + (yearsInBusiness == 1 ? " year" : " years"),
                yearsInBusiness >= required.get(), null);
    }

    static Outcome byPerson(QualificationRequirementType type, List<String> items, String one, String many,
                            String blankText, MatrixStatus personStatus) {
        if (items == null || items.isEmpty()) {
            return blank("The company profile " + blankText + ".");
        }
        String shown = items.stream().limit(SHOWN_ITEMS).collect(Collectors.joining(", "));
        String company = items.size() + " " + (items.size() == 1 ? one : many) + " listed: " + shown
                + (items.size() > SHOWN_ITEMS ? ", and more" : "");
        if (personStatus == null) {
            return new Outcome(company, MatrixStatus.UNKNOWN, Basis.AWAITING_PERSON,
                    type.label() + " is text, so a person compares it and sets the status.");
        }
        return new Outcome(company, personStatus, Basis.SET_BY_PERSON, null);
    }

    /** A whole number of up to four digits, or one written as a word, such as {@code three}. */
    static Optional<Integer> wholeNumber(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher d = DIGITS.matcher(text);
        if (d.find()) {
            return Optional.of(Integer.parseInt(d.group(1)));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("twenty-five") || lower.contains("twenty five")) {
            return Optional.of(25);
        }
        Matcher w = WORD.matcher(lower);
        while (w.find()) {
            Integer n = WORDS.get(w.group(1));
            if (n != null) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /** Rupees in crore, lakh or plain, as tenders print them: {@code ₹132 Cr}, {@code ₹80 lakh}. */
    static String rupees(BigDecimal amount) {
        if (amount.compareTo(CRORE) >= 0) {
            return "₹" + plain(amount.divide(CRORE, 2, RoundingMode.HALF_UP)) + " Cr";
        }
        if (amount.compareTo(LAKH) >= 0) {
            return "₹" + plain(amount.divide(LAKH, 2, RoundingMode.HALF_UP)) + " lakh";
        }
        return "₹" + plain(amount.setScale(2, RoundingMode.HALF_UP));
    }

    private static String plain(BigDecimal n) {
        BigDecimal s = n.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static int startYear(String financialYear) {
        return Integer.parseInt(financialYear.substring(0, 4));
    }

    private static Outcome compared(String company, boolean meets, String reason) {
        return new Outcome(company, meets ? MatrixStatus.MATCH : MatrixStatus.GAP, Basis.COMPARED, reason);
    }

    private static Outcome blank(String reason) {
        return new Outcome(null, MatrixStatus.UNKNOWN, Basis.PROFILE_BLANK, reason);
    }

    private static Outcome unreadable(String reason) {
        return new Outcome(null, MatrixStatus.UNKNOWN, Basis.NOT_READABLE, reason + " A person compares it.");
    }
}
