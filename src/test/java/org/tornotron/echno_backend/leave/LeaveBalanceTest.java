package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the two derived figures on {@link LeaveBalance}: available
 * (opening + accrued - used) and bookable (available - pending). The cases cover the
 * half-day arithmetic these figures have to survive, and the recurring accrual that
 * put a sixteen-digit number on the My Leaves screen.
 */
class LeaveBalanceTest {

    private LeaveBalance balance(double opening, double accrued, double used, double pending) {
        LeaveBalance balance = new LeaveBalance();
        balance.setOpeningBalance(opening);
        balance.setAccrued(accrued);
        balance.setUsed(used);
        balance.setPending(pending);
        return balance;
    }

    @Test
    void availableBalance_isOpeningPlusAccruedMinusUsed() {
        assertThat(balance(2.0, 9.0, 3.0, 0.0).getAvailableBalance()).isEqualTo(8.0);
    }

    @Test
    void availableBalance_carriesHalfDaysExactly() {
        // Three and a half days taken out of ten leaves six and a half.
        assertThat(balance(0.0, 10.0, 3.5, 0.0).getAvailableBalance()).isEqualTo(6.5);
    }

    @Test
    void availableBalance_recurringAccrualMinusAHalfDay_hasNoFloatingPointTail() {
        // Eight months of a 91-day quota, less one half-day taken.
        LeaveBalance balance = balance(0.0, 91.0 / 12.0 * 8, 0.5, 0.0);

        assertThat(balance.getAvailableBalance()).isEqualTo(60.17);
    }

    @Test
    void bookableBalance_subtractsPending() {
        assertThat(balance(0.0, 10.0, 2.0, 1.5).getBookableBalance()).isEqualTo(6.5);
    }

    @Test
    void bookableBalance_withAHalfDayPendingOnARecurringAccrual_hasNoFloatingPointTail() {
        LeaveBalance balance = balance(0.0, 91.0 / 12.0 * 8, 0.0, 0.5);

        assertThat(balance.getAvailableBalance()).isEqualTo(60.67);
        assertThat(balance.getBookableBalance()).isEqualTo(60.17);
    }
}
