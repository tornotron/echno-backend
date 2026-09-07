package org.tornotron.echno_backend.attendance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the field initialisers on the attendance entities to the builder, which is the only way
 * the application ever constructs them.
 *
 * <p>Lombok drops an inline initialiser on a {@code @Builder} class unless the field also carries
 * {@code @Builder.Default}: the generated builder passes its own uninitialised slot into the
 * all-args constructor, and the constructor assignment overwrites whatever the initialiser wrote.
 * The declaration then reads as a default that never applies, and the field arrives null.
 *
 * <p>These tests construct through {@code builder()} deliberately. The no-args constructor does
 * run the initialisers, so a fixture built with {@code new Attendance()} passes whether or not
 * the annotation is present and proves nothing. So does any builder chain that sets the field
 * under test explicitly. Every assertion below is on a field the chain leaves alone.
 */
class BuilderDefaultsTest {

    @Test
    @DisplayName("a built attendance carries zero minutes, not null, on every session field")
    void attendanceSessionMinutesDefaultToZero() {
        Attendance attendance = Attendance.builder().build();

        assertThat(attendance.getTotalWorkMinutes()).isZero();
        assertThat(attendance.getMorningSessionMinutes()).isZero();
        assertThat(attendance.getAfternoonSessionMinutes()).isZero();
        assertThat(attendance.getOvertimeMinutes()).isZero();
        assertThat(attendance.getBreakDurationMinutes()).isZero();
    }

    @Test
    @DisplayName("a built attendance starts PENDING approval")
    void attendanceApprovalStatusDefaultsToPending() {
        assertThat(Attendance.builder().build().getApprovalStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    @DisplayName("every attendance collection is an empty list, so addAttachment cannot throw")
    void attendanceCollectionsDefaultToEmptyLists() {
        Attendance attendance = Attendance.builder().build();

        assertThat(attendance.getClockEvents()).isNotNull().isEmpty();
        assertThat(attendance.getRegularizations()).isNotNull().isEmpty();
        assertThat(attendance.getMovements()).isNotNull().isEmpty();
        assertThat(attendance.getAttachments()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("a built shift timing carries the declared thresholds")
    void shiftTimingThresholdsSurviveTheBuilder() {
        ShiftTiming shift = ShiftTiming.builder().build();

        assertThat(shift.getGracePeriodMinutes()).isEqualTo(15);
        assertThat(shift.getMinimumWorkHours()).isEqualByComparingTo(BigDecimal.valueOf(8.0));
        assertThat(shift.getHalfDayWorkHours()).isEqualByComparingTo(BigDecimal.valueOf(4.0));
        assertThat(shift.getOvertimeThreshold()).isEqualByComparingTo(BigDecimal.valueOf(9.0));
    }

    @Test
    @DisplayName("a built regularization starts PENDING")
    void regularizationStatusDefaultsToPending() {
        assertThat(AttendanceRegularization.builder().build().getStatus())
                .isEqualTo(RegularizationStatus.PENDING);
    }
}
