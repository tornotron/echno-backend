package org.tornotron.echno_backend.labour;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LabourRepository extends JpaRepository<Labour,Long> {

    Optional<Labour> findByIdAndOrganization_Id(Long id, Long organizationId);
}
