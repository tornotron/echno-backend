package org.tornotron.echno_backend.leave;

import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.employee.Employee;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Whether a policy applies to an employee: the "Applies To" gender rule and the minimum service
 * months.
 *
 * <p>This is the same rule {@code LeavePolicyRepository.findApplicablePolicies} applies when the
 * balance screen lists what an employee may take, written once in Java so the apply flow refuses
 * the same policies the list withholds. Before this a request could be raised under any active
 * policy by naming its id, so a male employee could apply for maternity leave and a new joiner
 * for leave the policy withheld until six months' service. Gender is compared ignoring case,
 * because policies hold {@code FEMALE} while employee records hold {@code Female}; an employee
 * with no recorded gender is eligible only for policies open to everyone.
 */
final class LeavePolicyEligibility {

    private LeavePolicyEligibility() {
    }

    static boolean appliesTo(Employee employee, LeavePolicy policy) {
        return genderMatches(employee, policy) && serviceMet(employee, policy);
    }

    static void require(Employee employee, LeavePolicy policy) {
        if (!genderMatches(employee, policy)) {
            throw new InvalidRequestException(
                    "Leave policy '" + policy.getLeaveTypeName() + "' applies to "
                            + policy.getApplicableGenders().toLowerCase(Locale.ROOT)
                            + " employees, so it is not available to employee " + employee.getId());
        }
        if (!serviceMet(employee, policy)) {
            throw new InvalidRequestException(
                    "Leave policy '" + policy.getLeaveTypeName() + "' requires "
                            + policy.getMinServiceMonths() + " months of service, which employee "
                            + employee.getId() + " has not completed");
        }
    }

    private static boolean genderMatches(Employee employee, LeavePolicy policy) {
        String applies = policy.getApplicableGenders();
        if (applies == null || applies.isBlank() || "ALL".equalsIgnoreCase(applies.trim())) {
            return true;
        }
        String gender = employee.getGender();
        return gender != null && applies.trim().equalsIgnoreCase(gender.trim());
    }

    private static boolean serviceMet(Employee employee, LeavePolicy policy) {
        Integer required = policy.getMinServiceMonths();
        if (required == null || required <= 0) {
            return true;
        }
        LocalDateTime joining = employee.getJoiningDate();
        long served = joining == null ? 0 : ChronoUnit.MONTHS.between(joining, LocalDateTime.now());
        return served >= required;
    }
}
