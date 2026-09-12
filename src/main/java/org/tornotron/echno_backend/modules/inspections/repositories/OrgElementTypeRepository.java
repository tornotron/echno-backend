package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
