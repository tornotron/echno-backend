package org.tornotron.echno_backend.compliance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.compliance.CompliancePhase;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;

import java.util.UUID;

/**
 * A curated statutory-compliance rule the AI generation flow reasons over. This is
 * GLOBAL reference data shared across every tenant: it is deliberately NOT
 * tenant-scoped (no organization_id, no {@code orgFilter}), because the same
 * building-plan-approval or fire-NOC requirement applies to any org building in that
 * state. A rule is keyed by ({@code state}, {@code projectType}); the AI decides
 * which of the matching rules actually apply to a given project.
 */
@Entity
@Table(name = "compliance_rules",
        uniqueConstraints = @UniqueConstraint(name = "uk_compliance_rule_code",
                columnNames = {"state", "project_type", "code"}),
        indexes = {
                @Index(name = "idx_compliance_rule_state_type",
                        columnList = "state, project_type"),
                @Index(name = "idx_compliance_rule_active", columnList = "active")
        })
@Getter @Setter
@NoArgsConstructor
public class ComplianceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Indian state the compliance applies in (matched case-insensitively). */
    @Column(name = "state", nullable = false, length = 100)
    private String state;

    @Enumerated(EnumType.STRING)
    @Column(name = "project_type", nullable = false, length = 30)
    private org.tornotron.echno_backend.project.enums.ProjectType projectType;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 30)
    private CompliancePhase phase;

    /** Short stable code used as the dedupe reference on generated inspections. */
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_risk_level", nullable = false, length = 30)
    private ComplianceRiskLevel defaultRiskLevel;

    @Column(name = "resolution_options", columnDefinition = "TEXT")
    private String resolutionOptions;

    @Column(name = "authority", length = 200)
    private String authority;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
