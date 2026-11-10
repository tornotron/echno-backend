package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyProfile;

@Repository
public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, UUID> {

    /** The current tenant's profile, through the tenant filter; at most one row, by a unique key. */
    @Query("SELECT p FROM CompanyProfile p ORDER BY p.createdAt")
    List<CompanyProfile> findCurrent(Pageable page);
}
