package org.tornotron.echno_backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LeavePolicyRepository extends JpaRepository<LeavePolicy, Long> {

    List<LeavePolicy> findByOrganizationIdAndIsActiveTrue(Long organizationId);

    List<LeavePolicy> findByOrganizationId(Long organizationId);

    Optional<LeavePolicy> findByIdAndOrganization_Id(Long id, Long organizationId);

    Optional<LeavePolicy> findByOrganizationIdAndLeaveTypeCode(Long organizationId, String leaveTypeCode);

    boolean existsByOrganizationIdAndLeaveTypeCode(Long organizationId, String leaveTypeCode);

    /**
     * Whether a named organization already holds a policy with this leave-type code, asked
     * without the tenant filter narrowing it.
     *
     * <p>This exists for one caller: {@code duplicatePolicy}, which copies a policy into an
     * organization other than the one the request is scoped to. {@link #existsByOrganizationIdAndLeaveTypeCode}
     * cannot answer for that organization and never could. {@code LeavePolicy} carries
     * {@code orgFilter} as a query root, so Hibernate adds {@code organization_id = <tenant>} to
     * the query the caller wrote with {@code organization_id = <target>}, the two predicates name
     * different organizations, and the result is empty whatever the table holds. The uniqueness
     * rule was therefore skipped on exactly the path that needs it, and a real collision reached
     * {@code uk_leave_policy_org_type} and surfaced as a 500 rather than a 409. See #718.
     *
     * <p>Native, so the filter does not reach it, which is what makes it able to answer. That is a
     * deliberate cross-tenant read and is written to be as narrow as one can be:
     *
     * <ul>
     *   <li>It returns a count and never an entity, so nothing tenant-scoped is loaded and the
     *       fail-closed load listener has nothing to judge. The dispatcher queries on
     *       {@code ComplianceGenerationJobRepository} are scoped the same way and for the same
     *       reason.
     *   <li>It carries {@code organization_id = :organizationId} itself, so the tenant predicate
     *       is in the query rather than left to a filter that will not be applied.
     *   <li>It answers yes or no about a code the caller already supplied. It returns no row
     *       content, so a caller learns nothing about the target beyond the answer to the
     *       uniqueness question the endpoint exists to ask.
     * </ul>
     *
     * <p>The entitlement to read that organization at all is established before this is reached:
     * both duplicate endpoints require the caller to hold {@code system-admin} or {@code hr-admin}
     * in the target through {@code @orgSecurity.hasAnyOrgRole(#targetOrganizationId, ...)}. This
     * method assumes that and does not re-check it, so do not call it from anywhere that has not.
     *
     * @param organizationId The organization to ask about, which need not be the current tenant.
     * @param leaveTypeCode The code as it is stored, which is upper case.
     * @return How many policies that organization holds with that code; zero or one, since
     *         {@code uk_leave_policy_org_type} allows no more.
     */
    @Query(value = "SELECT count(*) FROM leave_policy "
            + "WHERE organization_id = :organizationId AND leave_type_code = :leaveTypeCode",
            nativeQuery = true)
    long countWithLeaveTypeCodeInOrganizationUnfiltered(
            @Param("organizationId") Long organizationId,
            @Param("leaveTypeCode") String leaveTypeCode);

    /**
     * The active policies an employee is eligible for, by gender and length of service.
     *
     * <p>The gender comparison is case-insensitive: a policy is configured as {@code FEMALE}
     * while an employee record holds {@code Female}, and an exact match would quietly make
     * such a policy apply to nobody.
     */
    @Query("SELECT lp FROM LeavePolicy lp WHERE lp.organization.id = :orgId " +
           "AND lp.isActive = true " +
           "AND (UPPER(lp.applicableGenders) = 'ALL' OR UPPER(lp.applicableGenders) = UPPER(:gender)) " +
           "AND (lp.minServiceMonths IS NULL OR lp.minServiceMonths <= :serviceMonths) " +
           "ORDER BY lp.displayOrder ASC")
    List<LeavePolicy> findApplicablePolicies(
            @Param("orgId") Long organizationId,
            @Param("gender") String gender,
            @Param("serviceMonths") Integer serviceMonths);

    List<LeavePolicy> findByOrganizationIdAndIsActiveTrueOrderByDisplayOrderAsc(Long organizationId);
}
