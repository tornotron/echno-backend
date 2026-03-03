package org.tornotron.echno_backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttendanceSettingsRepository extends JpaRepository<AttendanceSettings, Long> {

    Optional<AttendanceSettings> findByOrganizationIdAndProjectIdIsNullAndIsActiveTrue(Long organizationId);

    Optional<AttendanceSettings> findByOrganizationIdAndProjectIdAndIsActiveTrue(Long organizationId, Long projectId);

    List<AttendanceSettings> findByOrganizationIdAndIsActiveTrue(Long organizationId);
}
