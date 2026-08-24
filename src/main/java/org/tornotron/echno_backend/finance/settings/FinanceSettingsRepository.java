package org.tornotron.echno_backend.finance.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FinanceSettingsRepository extends JpaRepository<FinanceSettings, Long> {

    Optional<FinanceSettings> findByOrganization_Id(Long organizationId);
}
