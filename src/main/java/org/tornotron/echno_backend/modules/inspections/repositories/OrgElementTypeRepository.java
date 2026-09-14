package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.inspections.domain.OrgElementType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Finders name the organization as well as relying on the {@code orgFilter}; see OrgTradeRepository. */
@Repository
public interface OrgElementTypeRepository extends JpaRepository<OrgElementType, UUID> {

    @Query("SELECT e FROM OrgElementType e WHERE e.id = :id")
    Optional<OrgElementType> findByIdScoped(@Param("id") UUID id);

    Optional<OrgElementType> findByOrganizationIdAndCode(Long organizationId, String code);

    boolean existsByOrganizationIdAndCode(Long organizationId, String code);

    boolean existsByOrganizationIdAndCodeAndActiveTrue(Long organizationId, String code);

    List<OrgElementType> findByOrganizationIdOrderBySortOrderAscNameAsc(Long organizationId);

    List<OrgElementType> findByOrganizationIdAndActiveTrueOrderBySortOrderAscNameAsc(Long organizationId);

    @Query("SELECT e.catalogueCode FROM OrgElementType e WHERE e.organization.id = :organizationId AND e.catalogueCode IS NOT NULL")
    List<String> findCatalogueCodes(@Param("organizationId") Long organizationId);

    /**
     * Copies every active catalogue element type the organization does not have yet, in one
     * statement. {@code ON CONFLICT DO NOTHING} is what makes the first-use copy safe under
     * concurrency: two first requests both run it, the second finds the rows the first
     * committed and inserts nothing, and neither fails on {@code uk_org_element_type_code}.
     *
     * @return rows inserted, 0 when the organization already had the whole catalogue.
     */
    @Modifying
    @Query(value = "INSERT INTO org_element_types (organization_id, code, name, group_code, description, sort_order, active, catalogue_code) "
            + "SELECT :organizationId, c.code, c.name, c.group_code, c.description, c.sort_order, true, c.code "
            + "FROM element_type_catalogue c WHERE c.active "
            + "AND NOT EXISTS (SELECT 1 FROM org_element_types t WHERE t.organization_id = :organizationId AND t.code = c.code) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int copyCatalogueInto(@Param("organizationId") Long organizationId);
}
