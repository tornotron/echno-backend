package org.tornotron.echno_backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface LeaveCalendarRepository extends JpaRepository<LeaveCalendar, Long> {

    List<LeaveCalendar> findByLeaveRequestId(Long leaveRequestId);

    void deleteByLeaveRequestId(Long leaveRequestId);

    @Query("SELECT lc FROM LeaveCalendar lc " +
           "WHERE lc.organization.id = :orgId " +
           "AND lc.leaveDate >= :startDate " +
           "AND lc.leaveDate <= :endDate " +
           "ORDER BY lc.leaveDate ASC, lc.employeeName ASC")
    List<LeaveCalendar> findByOrganizationAndDateRange(
            @Param("orgId") Long organizationId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT lc FROM LeaveCalendar lc " +
           "WHERE lc.organization.id = :orgId " +
           "AND lc.department = :department " +
           "AND lc.leaveDate >= :startDate " +
           "AND lc.leaveDate <= :endDate " +
           "ORDER BY lc.leaveDate ASC, lc.employeeName ASC")
    List<LeaveCalendar> findByOrganizationAndDepartmentAndDateRange(
            @Param("orgId") Long organizationId,
            @Param("department") String department,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT lc FROM LeaveCalendar lc " +
           "WHERE lc.employee.id = :employeeId " +
           "AND lc.leaveDate >= :startDate " +
           "AND lc.leaveDate <= :endDate " +
           "ORDER BY lc.leaveDate ASC")
    List<LeaveCalendar> findByEmployeeAndDateRange(
            @Param("employeeId") Long employeeId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT lc FROM LeaveCalendar lc " +
           "WHERE lc.employee.id IN :employeeIds " +
           "AND lc.leaveDate >= :startDate " +
           "AND lc.leaveDate <= :endDate " +
           "ORDER BY lc.leaveDate ASC, lc.employeeName ASC")
    List<LeaveCalendar> findByEmployeeIdsAndDateRange(
            @Param("employeeIds") List<Long> employeeIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    List<LeaveCalendar> findByEmployeeIdAndLeaveDate(Long employeeId, LocalDate leaveDate);

    @Query("SELECT COUNT(DISTINCT lc.employee.id) FROM LeaveCalendar lc " +
           "WHERE lc.organization.id = :orgId " +
           "AND lc.leaveDate = :date")
    long countEmployeesOnLeaveByOrgAndDate(
            @Param("orgId") Long organizationId,
            @Param("date") LocalDate date);
}
