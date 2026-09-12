package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every finder names the organization as well as relying on the {@code orgFilter}, because the
 * lazy catalogue copy runs on the first read for a tenant and must never count another
 * tenant's rows as its own.
 */
@Repository
public interface OrgTradeRepository extends JpaRepository<OrgTrade, UUID> {

    @Query("SELECT t FROM OrgTrade t WHERE t.id = :id")
    Optional<OrgTrade> findByIdScoped(@Param("id") UUID id);

    Optional<OrgTrade> findByOrganizationIdAndCode(Long organizationId, String code);

    boolean existsByOrganizationIdAndCode(Long organizationId, String code);

    List<OrgTrade> findByOrganizationIdOrderBySortOrderAscNameAsc(Long organizationId);

    List<OrgTrade> findByOrganizationIdAndActiveTrueOrderBySortOrderAscNameAsc(Long organizationId);

    @Query("SELECT t.catalogueCode FROM OrgTrade t WHERE t.organization.id = :organizationId AND t.catalogueCode IS NOT NULL")
    List<String> findCatalogueCodes(@Param("organizationId") Long organizationId);
}
