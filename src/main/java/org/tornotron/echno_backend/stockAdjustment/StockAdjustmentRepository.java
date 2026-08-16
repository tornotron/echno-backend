package org.tornotron.echno_backend.stockAdjustment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    Optional<StockAdjustment> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByAdjustmentNumberAndOrganization_Id(String adjustmentNumber, Long organizationId);
}
