package org.tornotron.echno_backend.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkingWeekRepository extends JpaRepository<WorkingWeek, Long> {

    Optional<WorkingWeek> findByOrganization_Id(Long organizationId);
}
