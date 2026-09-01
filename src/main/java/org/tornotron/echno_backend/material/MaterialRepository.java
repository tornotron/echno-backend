package org.tornotron.echno_backend.material;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    Optional<Material> findBySku(String sku);

    List<Material> findByMaterialNameContainingIgnoreCase(String materialName);

    Optional<Material> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsBySkuAndOrganization_Id(String sku, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * The catalogue size, as a count rather than a page.
     *
     * <p>{@code /materials/web/all} already carries this on a page's {@code totalElements}, but
     * getting it that way costs a request whose only purpose is to be thrown away, and it cannot
     * travel on the same payload as the aggregates it is read beside. Counted here so the summary
     * answers with one call.
     *
     * <p>Written as JPQL with an explicit predicate rather than a derived
     * {@code countByOrganization_Id}, for the reason given on
     * {@code CurrentStockRepository.sumStockValueForOrganization}: these figures are read together
     * and must be scoped the same way as each other, including for a bypassed request.
     *
     * @param organizationId The tenant to count within.
     * @return How many materials the catalogue holds.
     */
    @Query("SELECT COUNT(m.id) FROM Material m WHERE m.organization.id = :organizationId")
    long countForOrganization(@Param("organizationId") Long organizationId);

    /**
     * How many distinct units of measure the catalogue uses.
     *
     * <p>The console counted these over the same capped array the stock value was summed over, so
     * it under-reported for the same reason. Unlike the value it is a count and could have been
     * fixed on the client, but only by loading the catalogue to count it, which is what the cap
     * exists to stop.
     *
     * @param organizationId The tenant to count within.
     * @return How many distinct units the catalogue's materials are measured in.
     */
    @Query("SELECT COUNT(DISTINCT m.unit) FROM Material m WHERE m.organization.id = :organizationId")
    long countDistinctUnitsForOrganization(@Param("organizationId") Long organizationId);
}
