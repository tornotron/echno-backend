package org.tornotron.echno_backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShiftTimingRepository extends JpaRepository<ShiftTiming, Long> {

    List<ShiftTiming> findByOrganizationId(Long organizationId);

    Optional<ShiftTiming> findByShiftNameAndOrganizationId(String shiftName, Long organizationId);
}
