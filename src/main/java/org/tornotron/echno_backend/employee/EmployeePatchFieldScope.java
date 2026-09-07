package org.tornotron.echno_backend.employee;

import org.springframework.security.access.AccessDeniedException;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Which fields of an employee record a person may set on themselves, and which belong to personnel.
 *
 * <p>{@code PATCH /employee/{id}} and its {@code /web} twin are gated on
 * {@code isSelfOrHasAnyOrgRole(#id, 'system-admin', 'hr-admin')}. The self clause is right for what
 * it was added for: maintaining your own phone number, email address, name and date of birth from
 * the phone is ordinary, and taking it away to solve this would take away the whole of employee
 * self-service. What the guard was never asked is <em>which field</em>, and the map the service
 * accepts reaches pay, employment status, the organizational identifier and the reporting line.
 * That is the split written down here. See #735.
 *
 * <h2>Why the line falls where it does</h2>
 *
 * <p>The eight administrative fields are things done <em>to</em> a person by the organization
 * rather than <em>by</em> them, and four of them are read elsewhere as statements the person did
 * not make:
 *
 * <ul>
 *   <li><b>salary</b> is set by the two roles this endpoint already names for everybody else, and
 *       {@code EmployeeDto} now shows it only to those two roles and to the person themselves. A
 *       field somebody may not read of a colleague is not a field they may write of themselves.
 *   <li><b>status</b> is the employment relationship. A closed record that can reopen itself is
 *       not closed.
 *   <li><b>employeeId</b> is the organization's identifier for the person, not the person's.
 *   <li><b>managerId</b> is the reporting line, and two approval flows route through it.
 *       {@code AttendanceGeofenceService.resolveApprover} sends an away-from-site attendance
 *       exception to the employee's manager, and {@code LeaveApprovalService.resolveApprovalChain}
 *       walks the same pointer to build the leave approvers. Both refuse an approver who is the
 *       subject, and neither can refuse an approver the subject chose. The organization already
 *       treats this field as personnel's: {@code assignManager} and {@code removeManager} are gated
 *       on {@code system-admin} and {@code hr-admin} with no self clause, so leaving it writable
 *       through the map was a way round those two endpoints rather than a decision about it.
 *   <li><b>designation</b> and <b>department</b> are organizational facts rather than personal
 *       ones. Both are set for the person at the point they join, off the invite code
 *       ({@code InviteCodeGenerationDto} requires them and the joiner never supplies them), and
 *       both are shown to colleagues as the organization's description of the person: designation
 *       is what a leave approver is labelled with on {@code LeaveApprovalDto}. Neither decides an
 *       authorization today, which is what was checked before deciding. Department is the closer
 *       call, and it goes the same way for a second reason: it is already a partition key, the one
 *       the department leave calendar and the employee search divide on, so a self-set department
 *       is a person moving themselves between partitions somebody else drew.
 *   <li><b>joiningDate</b> is not on the list #735 opens with, and belongs there. It is the start
 *       date leave accrual counts from ({@code LeaveAccrualService.getStartMonth}) and that
 *       {@code LeaveBalanceService} prorates the first year's balance against, so a person who can
 *       backdate it grants themselves leave.
 *   <li><b>shiftTimingId</b> likewise: {@code AttendanceService} prefers the employee's assigned
 *       shift over the one on the request, and the shift is what late arrival and overtime are
 *       measured against. Choosing your own shift is choosing when you are late.
 * </ul>
 *
 * <p>What is left is genuinely the person's own: their name, phone number, email address and date
 * of birth. Nothing in the application decides anything on those four.
 *
 * <h2>Why this cannot be forgotten</h2>
 *
 * <p>Two things hold it, and the second is the one that matters.
 *
 * <p>First, the check runs in {@code EmployeeService.partialUpdateAnEmployee(Map, Employee)}, the
 * one private method every route into this map funnels through: both single-record twins and the
 * batch endpoint. A new endpoint accepting the same map reaches the same method and is covered
 * without its author knowing this class exists. It is deliberately not a second request DTO or a
 * {@code @JsonView}, because both of those are chosen at the call site and a call site that forgets
 * them fails open and silently. This one cannot be omitted at a call site because there is no call
 * site to omit it at.
 *
 * <p>Second, {@code EmployeePatchFieldScopeContractTest} reads the {@code case} labels out of that
 * method's own source, the same way {@code PartialUpdateSchemaContractTest} does, and fails when a
 * key is accepted that appears in neither set below. A field added to the switch has to be
 * classified before the build goes green, so the failure mode this replaces, a new field quietly
 * joining the self-editable surface, is a red build rather than a discovery.
 *
 * <p>The decision is on the caller's <em>role</em> and not on whether the record is theirs. An
 * {@code hr-admin} editing their own record still sets their own pay, because they hold the role
 * that sets pay; and a caller with neither role can only have reached the record through the self
 * clause, so refusing the administrative fields to anyone without the roles refuses exactly the
 * self-caller. Keying it on the role rather than on the subject also means a future endpoint that
 * admits a third role to this map does not silently hand that role the pay field.
 */
public final class EmployeePatchFieldScope {

    /**
     * The roles that may set the administrative fields. The same pair the PATCH names, the same
     * pair {@code EmployeeDto} shows pay to.
     */
    public static final String[] PERSONNEL_ROLES = {"system-admin", "hr-admin"};

    /** The fields a person may set on their own record. */
    public static final Set<String> SELF_EDITABLE = Set.of(
            "employeeName",
            "phoneNumber",
            "emailAddress",
            "dateOfBirth");

    /** The fields only {@link #PERSONNEL_ROLES} may set, on anybody's record including their own. */
    public static final Set<String> PERSONNEL_ONLY = Set.of(
            "employeeId",
            "status",
            "designation",
            "department",
            "joiningDate",
            "salary",
            "shiftTimingId",
            "managerId");

    private EmployeePatchFieldScope() {
    }

    /**
     * Refuses an update that names a field the caller may not set.
     *
     * <p>Refuses rather than dropping. A silently dropped field answers 200 and changes nothing,
     * which is the shape that made an issue's type unchangeable for months and the reason
     * {@code PartialUpdateKeys} exists; a caller told their salary change succeeded, and an
     * administrator reading the record later, would both be reading a lie. The message names every
     * refused field rather than the first, so a client is not walked through them one request at a
     * time.
     *
     * <p>The whole update is refused, including the fields the caller was entitled to change.
     * Applying the permitted half of a payload the caller was not entitled to send would turn a
     * refusal into a partial success and leave the record in a state neither side asked for.
     *
     * @param keys The keys the update carries.
     * @param callerHoldsPersonnelRole Whether the caller holds one of {@link #PERSONNEL_ROLES} in
     *     the organization this request is scoped to.
     * @throws AccessDeniedException if the caller holds neither role and the update names one of
     *     {@link #PERSONNEL_ONLY}.
     */
    public static void refuseFieldsTheCallerMayNotSet(Collection<String> keys,
                                                      boolean callerHoldsPersonnelRole) {
        if (callerHoldsPersonnelRole) {
            return;
        }
        List<String> refused = keys.stream()
                .filter(PERSONNEL_ONLY::contains)
                .sorted()
                .toList();
        if (refused.isEmpty()) {
            return;
        }
        throw new AccessDeniedException(
                "These fields are set by your organization rather than by you, so this update was "
                        + "not applied: " + String.join(", ", refused)
                        + ". Ask a system administrator or an HR administrator to change them. You "
                        + "can still change " + String.join(", ", SELF_EDITABLE.stream().sorted().toList())
                        + " on your own record.");
    }
}
