package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the money helpers that underpin every financial calculation:
 * scale/rounding on store and display, the null-safe sign checks, and null-tolerant
 * summation. Pure functions, so no Spring or database.
 */
class MoneyUtilsTest {

    // --- normalize: store scale (4dp), HALF_UP ---------------------------

    @Test
    void normalize_setsFourDecimalPlaces() {
        assertThat(MoneyUtils.normalize(new BigDecimal("10")).scale()).isEqualTo(MoneyUtils.SCALE);
        assertThat(MoneyUtils.normalize(new BigDecimal("10"))).isEqualByComparingTo("10.0000");
    }

    @Test
    void normalize_roundsHalfUpAtStoreScale() {
        assertThat(MoneyUtils.normalize(new BigDecimal("1.23455"))).isEqualByComparingTo("1.2346");
        assertThat(MoneyUtils.normalize(new BigDecimal("1.23454"))).isEqualByComparingTo("1.2345");
    }

    @Test
    void normalize_nullBecomesZeroAtStoreScale() {
        BigDecimal zero = MoneyUtils.normalize(null);
        assertThat(zero.signum()).isZero();
        assertThat(zero.scale()).isEqualTo(MoneyUtils.SCALE);
    }

    // --- forDisplay: display scale (2dp), HALF_UP ------------------------

    @Test
    void forDisplay_roundsHalfUpToTwoDecimalPlaces() {
        assertThat(MoneyUtils.forDisplay(new BigDecimal("1.235"))).isEqualByComparingTo("1.24");
        assertThat(MoneyUtils.forDisplay(new BigDecimal("1.234"))).isEqualByComparingTo("1.23");
        assertThat(MoneyUtils.forDisplay(new BigDecimal("1.2")).scale()).isEqualTo(MoneyUtils.DISPLAY_SCALE);
    }

    @Test
    void forDisplay_nullBecomesZero() {
        assertThat(MoneyUtils.forDisplay(null).signum()).isZero();
    }

    // --- sign checks (null-safe) -----------------------------------------

    @Test
    void isZero_trueForNullZeroAndScaledZero() {
        assertThat(MoneyUtils.isZero(null)).isTrue();
        assertThat(MoneyUtils.isZero(BigDecimal.ZERO)).isTrue();
        assertThat(MoneyUtils.isZero(new BigDecimal("0.00"))).isTrue();
        assertThat(MoneyUtils.isZero(new BigDecimal("0.01"))).isFalse();
    }

    @Test
    void isPositive_onlyForStrictlyPositive() {
        assertThat(MoneyUtils.isPositive(new BigDecimal("0.01"))).isTrue();
        assertThat(MoneyUtils.isPositive(BigDecimal.ZERO)).isFalse();
        assertThat(MoneyUtils.isPositive(new BigDecimal("-1"))).isFalse();
        assertThat(MoneyUtils.isPositive(null)).isFalse();
    }

    @Test
    void isNegative_onlyForStrictlyNegative() {
        assertThat(MoneyUtils.isNegative(new BigDecimal("-0.01"))).isTrue();
        assertThat(MoneyUtils.isNegative(BigDecimal.ZERO)).isFalse();
        assertThat(MoneyUtils.isNegative(new BigDecimal("1"))).isFalse();
        assertThat(MoneyUtils.isNegative(null)).isFalse();
    }

    // --- sum: null-tolerant, normalized ----------------------------------

    @Test
    void sum_addsValuesAndSkipsNulls() {
        assertThat(MoneyUtils.sum(new BigDecimal("10"), null, new BigDecimal("2.5")))
                .isEqualByComparingTo("12.5");
    }

    @Test
    void sum_ofNothingIsZeroAtStoreScale() {
        BigDecimal total = MoneyUtils.sum();
        assertThat(total.signum()).isZero();
        assertThat(total.scale()).isEqualTo(MoneyUtils.SCALE);
    }
}
