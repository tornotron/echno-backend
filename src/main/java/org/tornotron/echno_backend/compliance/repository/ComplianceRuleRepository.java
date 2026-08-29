package org.tornotron.echno_backend.compliance.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Access to the global compliance-rule reference data. Not tenant-scoped: these
 * rules are shared across all organizations, so no {@code orgFilter} applies.
 */
@Repository
public interface ComplianceRuleRepository extends JpaRepository<ComplianceRule, UUID> {

    /**
     * The candidate rules for a project: every active rule registered for the
     * project's state (case-insensitive) and type. The AI then decides which of
     * these actually apply.
     */
    List<ComplianceRule> findByStateIgnoreCaseAndProjectTypeAndActiveTrue(String state,
                                                                          ProjectType projectType);

    /**
     * The newest {@code effectiveFrom} in each jurisdiction, over active rules only.
     *
     * <p>This is the whole of what the sweep needs from the catalogue, and it is one query for
     * every project rather than one per project. The table is global reference data measured in
     * tens of rows, so the grouped result is small enough to hold in memory for a pass and
     * compare against each candidate, which is what keeps the sweep from issuing a query per
     * project across every tenant.
     *
     * <p>Inactive rules are excluded because a rule that is switched off cannot make anything
     * stale: generation would not consider it, so re-assessing a project on account of it would
     * spend a model call to produce the answer the project already has.
     */
    @Query("SELECT r.state AS state, r.projectType AS projectType, "
            + "MAX(r.effectiveFrom) AS newestEffectiveFrom "
            + "FROM ComplianceRule r WHERE r.active = true "
            + "GROUP BY r.state, r.projectType")
    List<JurisdictionChange> findNewestEffectiveFromByJurisdiction();

    /** One jurisdiction and the most recent moment anything in it came into force. */
    interface JurisdictionChange {
        String getState();

        ProjectType getProjectType();

        LocalDateTime getNewestEffectiveFrom();
    }
}
