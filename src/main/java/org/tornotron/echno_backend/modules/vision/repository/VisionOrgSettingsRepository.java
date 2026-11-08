package org.tornotron.echno_backend.modules.vision.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.modules.vision.domain.VisionOrgSettings;

public interface VisionOrgSettingsRepository extends JpaRepository<VisionOrgSettings, Long> {
}
