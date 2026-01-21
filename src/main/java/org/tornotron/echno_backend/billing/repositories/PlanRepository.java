package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.billing.Plan;

import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByCodeAndIsActiveTrue(String code);

    @Query("SELECT p FROM Plan p LEFT JOIN FETCH p.planFeatures pf " +
            "LEFT JOIN FETCH pf.feature WHERE p.code = :code AND p.isActive = true")
    Optional<Plan> findByCodeWithFeatures(@Param("code") String code);

    List<Plan> findByIsActiveTrueAndIsPublicTrueOrderBySortOrder();

    @Query("SELECT p FROM Plan p LEFT JOIN FETCH p.planFeatures pf " +
            "LEFT JOIN FETCH pf.feature WHERE p.id = :planId")
    Optional<Plan> findByIdWithFeatures(@Param("planId") Long planId);

    @Query("SELECT DISTINCT p FROM Plan p LEFT JOIN FETCH p.planFeatures pf " +
            "LEFT JOIN FETCH pf.feature WHERE p.isActive = true AND p.isPublic = true ORDER BY p.sortOrder")
    List<Plan> findPublicPlansWithFeatures();

    @Query("SELECT DISTINCT p FROM Plan p LEFT JOIN FETCH p.planFeatures pf " +
            "LEFT JOIN FETCH pf.feature")
    List<Plan> findAllWithFeatures();
}
