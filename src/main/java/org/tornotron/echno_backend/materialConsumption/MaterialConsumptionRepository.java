package org.tornotron.echno_backend.materialConsumption;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;

import java.time.LocalDateTime;
import java.util.List;

public interface MaterialConsumptionRepository extends JpaRepository<MaterialConsumption, Long> {

    List<MaterialConsumption> findByMaterialId(Long materialId);

    List<MaterialConsumption> findByConsumptionType(MaterialConsumptionType consumptionType);

    List<MaterialConsumption> findByConsumptionDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    List<MaterialConsumption> findByCreatedById(Long userId);

    List<MaterialConsumption> findByTaskId(Long taskId);
}
