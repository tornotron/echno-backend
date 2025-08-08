package org.tornotron.echno_backend.organization;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    Optional<Organization> findOrganizationByOrganizationName(@NotBlank(message = "organizationName is required") @Size(min = 3, max = 50,message = "organizationName must be between 3 and 50 characters") String organizationName);
}
