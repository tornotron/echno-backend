package org.tornotron.echno_backend.common.configuration;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyUtils {

    public static final int SCALE = 4;             // store at 4 dp
    public static final int DISPLAY_SCALE = 2;     // present at 2 dp
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyUtils() {}

    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        return value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal forDisplay(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(DISPLAY_SCALE, ROUNDING);
        return value.setScale(DISPLAY_SCALE, ROUNDING);
    }

    public static boolean isZero(BigDecimal v) {
        return v == null || v.signum() == 0;
    }

    public static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    public static boolean isNegative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }

    public static BigDecimal sum(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            if (v != null) total = total.add(v);
        }
        return normalize(total);
    }
}
