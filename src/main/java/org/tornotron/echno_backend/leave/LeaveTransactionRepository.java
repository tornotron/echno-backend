package org.tornotron.echno_backend.leave;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.leave.enums.TransactionType;

import java.time.LocalDate;
import java.util.List;

public interface LeaveTransactionRepository extends JpaRepository<LeaveTransaction, Long> {

    List<LeaveTransaction> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    Page<LeaveTransaction> findByEmployeeId(Long employeeId, Pageable pageable);

    List<LeaveTransaction> findByLeaveBalanceIdOrderByCreatedAtDesc(Long leaveBalanceId);

    List<LeaveTransaction> findByLeaveRequestId(Long leaveRequestId);

    @Query("SELECT lt FROM LeaveTransaction lt " +
           "WHERE lt.employee.id = :employeeId " +
           "AND lt.transactionDate >= :startDate " +
           "AND lt.transactionDate <= :endDate " +
           "ORDER BY lt.transactionDate DESC, lt.createdAt DESC")
    List<LeaveTransaction> findByEmployeeIdAndDateRange(
            @Param("employeeId") Long employeeId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT lt FROM LeaveTransaction lt " +
           "WHERE lt.leaveBalance.id = :balanceId " +
           "AND lt.transactionType = :type " +
           "AND lt.referenceMonth = :month " +
           "AND lt.referenceYear = :year")
    List<LeaveTransaction> findByBalanceAndTypeAndMonthYear(
            @Param("balanceId") Long balanceId,
            @Param("type") TransactionType type,
            @Param("month") Integer month,
            @Param("year") Integer year);

    @Query("SELECT COALESCE(SUM(lt.days), 0) FROM LeaveTransaction lt " +
           "WHERE lt.leaveBalance.id = :balanceId " +
           "AND lt.transactionType = :type")
    Double sumDaysByBalanceAndType(
            @Param("balanceId") Long balanceId,
            @Param("type") TransactionType type);

    boolean existsByLeaveBalanceIdAndTransactionTypeAndReferenceMonthAndReferenceYear(
            Long balanceId, TransactionType type, Integer month, Integer year);
}
