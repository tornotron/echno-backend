package org.tornotron.echno_backend.modules.documentingestion.extraction.values;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TenderValuesTest {

    @Test
    void amountsReadIndianGroupingCroreAndLakh() {
        assertThat(TenderValues.amount("Rs. 4,20,00,000/-")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("42000000"));
        assertThat(TenderValues.amount("Rs 1.25 crore")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("12500000"));
        assertThat(TenderValues.amount("80 lakh")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("8000000"));
        assertThat(TenderValues.amount("INR 80,000.50")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("80000.50"));
        assertThat(TenderValues.amount("Rs. 5,000 or Rs. 6,000")).as("two numbers, either could be meant").isEmpty();
        assertThat(TenderValues.amount("Nil")).isEmpty();
    }

    @Test
    void percentagesAndPlainNumbers() {
        assertThat(TenderValues.percent("1.25 % Tendered value")).contains(new BigDecimal("1.25"));
        assertThat(TenderValues.percent("20 per cent")).contains(new BigDecimal("20"));
        assertThat(TenderValues.percentOrNumber("20")).contains(new BigDecimal("20"));
        assertThat(TenderValues.percentOrNumber("twenty")).isEmpty();
    }

    @Test
    void datesAreDayFirstWithOrWithoutATime() {
        assertThat(TenderValues.date("25.11.2026")).contains(LocalDate.of(2026, 11, 25));
        assertThat(TenderValues.date("25/11/26")).contains(LocalDate.of(2026, 11, 25));
        assertThat(TenderValues.date("25th November 2026")).contains(LocalDate.of(2026, 11, 25));
        assertThat(TenderValues.date("Nov 25, 2026")).contains(LocalDate.of(2026, 11, 25));
        assertThat(TenderValues.date("2026-11-25")).contains(LocalDate.of(2026, 11, 25));
        assertThat(TenderValues.date("31.02.2026")).isEmpty();
        assertThat(TenderValues.dateTime("Up to 5.30 PM of 21.06.2021")).contains(LocalDateTime.of(2021, 6, 21, 17, 30));
        assertThat(TenderValues.dateTime("25.11.2026 at 15:00 hrs")).contains(LocalDateTime.of(2026, 11, 25, 15, 0));
        assertThat(TenderValues.dateTime("25.11.2026")).contains(LocalDateTime.of(2026, 11, 25, 23, 59));
    }

    @Test
    void spansCountFromTheStart() {
        LocalDate start = LocalDate.of(2027, 1, 31);
        assertThat(TenderValues.span("Month 4")).hasValueSatisfying(s -> assertThat(s.from(start)).isEqualTo(LocalDate.of(2027, 5, 31)));
        assertThat(TenderValues.span("120 (one hundred twenty) days from the date of start"))
                .hasValueSatisfying(s -> assertThat(s.from(start)).isEqualTo(start.plusDays(120)));
        assertThat(TenderValues.span("3 Months from the Date of LOA")).contains(new TenderValues.Span(3, TenderValues.Unit.MONTH));
        assertThat(TenderValues.span("Within 2 weeks")).contains(new TenderValues.Span(2, TenderValues.Unit.WEEK));
        assertThat(TenderValues.span("Completion of works")).isEmpty();
        assertThat(TenderValues.days("90 days")).contains(90);
        assertThat(TenderValues.days("3 months")).contains(90);
    }

    @Test
    void aSignedValueOrAPercentageIsNotReadAsAnAmount() {
        assertThat(TenderValues.amount("Rs. -5000")).isEmpty();
        assertThat(TenderValues.amount("Rs.-5,000")).isEmpty();
        assertThat(TenderValues.amount("2% of estimated cost")).as("a percentage EMD has no amount").isEmpty();
        assertThat(TenderValues.amount("2 per cent of the estimate")).isEmpty();
        assertThat(TenderValues.amount("Rs. 4,20,00,000/-")).isPresent();
        assertThat(TenderValues.percent("-5%")).isEmpty();
        assertThat(TenderValues.percent("2-3%")).as("a range keeps its second figure").contains(new BigDecimal("3"));
    }

    @Test
    void anHourWithNoMinutesIsReadWithItsAmOrPm() {
        assertThat(TenderValues.dateTime("25 Nov 2026 at 3 PM")).contains(LocalDateTime.of(2026, 11, 25, 15, 0));
        assertThat(TenderValues.dateTime("25.11.2026, 11 a.m.")).contains(LocalDateTime.of(2026, 11, 25, 11, 0));
        assertThat(TenderValues.dateTime("25.11.2026 12 am")).contains(LocalDateTime.of(2026, 11, 25, 0, 0));
        assertThat(TenderValues.dateTime("25.11.2026, 5 amendments")).contains(LocalDateTime.of(2026, 11, 25, 23, 59));
    }

    @Test
    void aBareNumberIsDaysAndAFractionalOrAbsurdSpanIsNotRead() {
        assertThat(TenderValues.days("90")).contains(90);
        assertThat(TenderValues.days(" 120 ")).contains(120);
        assertThat(TenderValues.span("Month 4.5")).isEmpty();
        assertThat(TenderValues.span("Month 4,5")).isEmpty();
        assertThat(TenderValues.span("2147483648 days")).isEmpty();
        assertThat(TenderValues.span("Month 2147483648")).isEmpty();
        assertThat(TenderValues.days("2147483648 days")).isEmpty();
    }

    @Test
    void aSpanCountedFromAnotherEventIsNotCountedFromTheStart() {
        assertThat(TenderValues.spanFromStart("3 Months from the Date of LOA")).isEmpty();
        assertThat(TenderValues.spanFromStart("30 days after award of work")).isEmpty();
        assertThat(TenderValues.spanFromStart("Month 4")).contains(new TenderValues.Span(4, TenderValues.Unit.MONTH));
        assertThat(TenderValues.spanFromStart("120 (one hundred twenty) days from the date of start")).isPresent();
        assertThat(TenderValues.spanFromStart("within 90 days of the start")).isPresent();
        assertThat(TenderValues.spanFromStart("6 months from commencement")).isPresent();
    }
}
