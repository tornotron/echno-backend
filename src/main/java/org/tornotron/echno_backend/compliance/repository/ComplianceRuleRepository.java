package org.tornotron.echno_backend.compliance.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.project.enums.ProjectType;

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
}
