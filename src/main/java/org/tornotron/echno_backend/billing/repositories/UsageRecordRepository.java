package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.billing.UsageRecord;

import java.time.Instant;
import java.util.List;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    @Query("SELECT COALESCE(SUM(ur.usageAmount), 0) FROM UsageRecord ur " +
           "WHERE ur.organizationId = :organizationId AND ur.featureId = :featureId " +
           "AND ur.periodStart >= :periodStart AND ur.periodEnd <= :periodEnd")
    Long sumUsageForPeriod(
            @Param("organizationId") Long organizationId,
            @Param("featureId") Long featureId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd
    );

    @Query("SELECT ur.featureId, SUM(ur.usageAmount) FROM UsageRecord ur " +
           "WHERE ur.organizationId = :organizationId AND ur.periodStart >= :periodStart " +
           "GROUP BY ur.featureId")
    List<Object[]> getOrganizationUsageByFeature(
            @Param("organizationId")Long organizationId,
            @Param("periodStart")Instant periodStart
    );
}
