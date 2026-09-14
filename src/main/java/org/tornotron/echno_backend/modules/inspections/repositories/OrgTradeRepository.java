package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Copies every active catalogue trade the organization does not have yet, in one
     * statement. {@code ON CONFLICT DO NOTHING} is what makes the first-use copy safe under
     * concurrency: two first requests both run it, the second finds the rows the first
     * committed and inserts nothing, and neither fails on {@code uk_inspection_trade_code}.
     *
     * @return rows inserted, 0 when the organization already had the whole catalogue.
     */
    @Modifying
    @Query(value = "INSERT INTO inspection_trades (organization_id, code, name, group_code, description, sort_order, active, catalogue_code) "
            + "SELECT :organizationId, c.code, c.name, c.group_code, c.description, c.sort_order, true, c.code "
            + "FROM trade_catalogue c WHERE c.active "
            + "AND NOT EXISTS (SELECT 1 FROM inspection_trades t WHERE t.organization_id = :organizationId AND t.code = c.code) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int copyCatalogueInto(@Param("organizationId") Long organizationId);
}
