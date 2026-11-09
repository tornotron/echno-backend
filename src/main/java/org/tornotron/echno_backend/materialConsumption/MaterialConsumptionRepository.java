package org.tornotron.echno_backend.materialConsumption;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MaterialConsumptionRepository extends JpaRepository<MaterialConsumption, Long>,
        JpaSpecificationExecutor<MaterialConsumption> {

    Optional<MaterialConsumption> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<MaterialConsumption> findByMaterialId(Long materialId);

    Page<MaterialConsumption> findByMaterialId(Long materialId, Pageable pageable);

    List<MaterialConsumption> findByConsumptionType(MaterialConsumptionType consumptionType);

    Page<MaterialConsumption> findByConsumptionType(MaterialConsumptionType consumptionType, Pageable pageable);

    List<MaterialConsumption> findByConsumptionDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    Page<MaterialConsumption> findByConsumptionDateBetween(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    List<MaterialConsumption> findByCreatedById(Long userId);

    List<MaterialConsumption> findByTaskId(Long taskId);

    Page<MaterialConsumption> findByTaskId(Long taskId, Pageable pageable);
}
