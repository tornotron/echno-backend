package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.employee.Employee;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeavePolicyEligibilityTest {

    private static Employee employee(String gender, LocalDateTime joined) {
        Employee e = new Employee();
        e.setId(7L);
        e.setGender(gender);
        e.setJoiningDate(joined);
        return e;
    }

    private static LeavePolicy policy(String appliesTo, int minServiceMonths) {
        LeavePolicy p = new LeavePolicy();
        p.setLeaveTypeName("Test");
        p.setApplicableGenders(appliesTo);
        p.setMinServiceMonths(minServiceMonths);
        return p;
    }

    @Test
    void genderIsComparedIgnoringCase_andAllOpensToEveryone() {
        assertThat(LeavePolicyEligibility.appliesTo(employee("Female", null), policy("FEMALE", 0))).isTrue();
        assertThat(LeavePolicyEligibility.appliesTo(employee("male", null), policy("FEMALE", 0))).isFalse();
        assertThat(LeavePolicyEligibility.appliesTo(employee("male", null), policy("ALL", 0))).isTrue();
        assertThat(LeavePolicyEligibility.appliesTo(employee(null, null), policy("ALL", 0))).isTrue();
        assertThat(LeavePolicyEligibility.appliesTo(employee(null, null), policy("MALE", 0))).isFalse();
        assertThat(LeavePolicyEligibility.appliesTo(employee("male", null), policy(null, 0))).isTrue();
    }

    @Test
    void serviceMonthsAreCountedFromTheJoiningDate() {
        LocalDateTime twoMonthsAgo = LocalDateTime.now().minusMonths(2);
        assertThat(LeavePolicyEligibility.appliesTo(employee("male", twoMonthsAgo), policy("ALL", 6))).isFalse();
        assertThat(LeavePolicyEligibility.appliesTo(employee("male", twoMonthsAgo), policy("ALL", 2))).isTrue();
        assertThat(LeavePolicyEligibility.appliesTo(employee("male", null), policy("ALL", 6))).isFalse();
    }

    @Test
    void require_namesTheRuleThatRefused() {
        assertThatThrownBy(() -> LeavePolicyEligibility.require(employee("Male", null), policy("FEMALE", 0)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("applies to female employees");
        assertThatThrownBy(() -> LeavePolicyEligibility.require(employee("Male", LocalDateTime.now()), policy("ALL", 6)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("6 months of service");
    }
}
