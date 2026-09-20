package org.tornotron.echno_backend.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    Optional<Holiday> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<Holiday> findByOrganization_IdAndHolidayDateBetweenOrderByHolidayDateAsc(
            Long organizationId, LocalDate from, LocalDate to);

    boolean existsByOrganization_IdAndHolidayDate(Long organizationId, LocalDate holidayDate);

    boolean existsByOrganization_IdAndHolidayDateAndIdNot(Long organizationId, LocalDate holidayDate, Long id);
}
