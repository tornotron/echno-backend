package org.tornotron.echno_backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LeavePolicyRepository extends JpaRepository<LeavePolicy, Long> {

    List<LeavePolicy> findByOrganizationIdAndIsActiveTrue(Long organizationId);

    List<LeavePolicy> findByOrganizationId(Long organizationId);

    Optional<LeavePolicy> findByOrganizationIdAndLeaveTypeCode(Long organizationId, String leaveTypeCode);

    boolean existsByOrganizationIdAndLeaveTypeCode(Long organizationId, String leaveTypeCode);

    @Query("SELECT lp FROM LeavePolicy lp WHERE lp.organization.id = :orgId " +
           "AND lp.isActive = true " +
           "AND (lp.applicableGenders = 'ALL' OR lp.applicableGenders = :gender) " +
           "AND (lp.minServiceMonths IS NULL OR lp.minServiceMonths <= :serviceMonths) " +
           "ORDER BY lp.displayOrder ASC")
    List<LeavePolicy> findApplicablePolicies(
            @Param("orgId") Long organizationId,
            @Param("gender") String gender,
            @Param("serviceMonths") Integer serviceMonths);

    List<LeavePolicy> findByOrganizationIdAndIsActiveTrueOrderByDisplayOrderAsc(Long organizationId);
}
