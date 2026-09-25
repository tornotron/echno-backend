package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * When a leave may start before today: only under a policy that asks for no advance notice, and
 * no more than {@link LeaveRequestValidator#MAX_BACKDATED_DAYS} days back. This is what lets the
 * regularization calendar send an employee to apply for leave on a day they missed.
 */
class LeaveBackdatedStartTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    private static LeavePolicy policy(Integer noticeDays) {
        LeavePolicy policy = new LeavePolicy();
        policy.setLeaveTypeName("Sick Leave");
        policy.setAdvanceNoticeDays(noticeDays);
        return policy;
    }

    @Test
    void aPastDayIsAllowedWhenThePolicyNeedsNoNotice() {
        assertThatCode(() -> LeaveRequestValidator.requireStartDateAllowed(
                policy(0), TODAY.minusDays(3), TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> LeaveRequestValidator.requireStartDateAllowed(
                policy(null), TODAY.minusDays(LeaveRequestValidator.MAX_BACKDATED_DAYS), TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    void aPastDayIsRefusedWhenThePolicyNeedsNotice() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> LeaveRequestValidator.requireStartDateAllowed(
                        policy(2), TODAY.minusDays(1), TODAY));
    }

    @Test
    void aDayBeyondTheWindowIsRefused() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> LeaveRequestValidator.requireStartDateAllowed(
                        policy(0), TODAY.minusDays(LeaveRequestValidator.MAX_BACKDATED_DAYS + 1), TODAY));
    }

    @Test
    void todayAndLaterAreUnaffected() {
        assertThatCode(() -> LeaveRequestValidator.requireStartDateAllowed(policy(5), TODAY, TODAY))
                .doesNotThrowAnyException();
    }
}
