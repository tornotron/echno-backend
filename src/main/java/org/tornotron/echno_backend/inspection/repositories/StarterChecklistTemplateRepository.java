package org.tornotron.echno_backend.inspection.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.domain.StarterChecklistTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StarterChecklistTemplateRepository
        extends JpaRepository<StarterChecklistTemplate, UUID> {

    /**
     * Every starter on offer. Bounded by what the table is rather than by how much
     * data a tenant has accumulated: there is at most one starter per
     * {@link InspectionTrade}, and the enum has sixteen members. It is global
     * reference data shipped by a Liquibase seed, so no tenant can grow it.
     */
    List<StarterChecklistTemplate> findByActiveTrueOrderByTradeAsc();

    Optional<StarterChecklistTemplate> findByTradeAndActiveTrue(InspectionTrade trade);
}
