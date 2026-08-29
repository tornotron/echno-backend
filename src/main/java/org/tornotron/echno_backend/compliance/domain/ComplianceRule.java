package org.tornotron.echno_backend.compliance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.compliance.CompliancePhase;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;

import java.time.LocalDateTime;
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

    /** When this row first appeared. Audit only; nothing schedules off it. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When this row was last written, for any reason at all. Audit only. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * When the rule came into force, as the catalogue's curator states it. This is the only
     * timestamp the nightly sweep reads, and the distinction from {@link #updatedAt} is the
     * whole reason it exists.
     *
     * <p>{@code updatedAt} moves on every write, including a corrected typo in
     * {@link #description} and including a bulk re-seed that rewrites rows whose content did
     * not change. A sweep keyed on it would treat a comma fix across the catalogue as the
     * whole catalogue changing, and re-assess every approved project in every tenant against
     * rules whose meaning is identical. That is expensive and it is also wrong: nothing about
     * the answer changed.
     *
     * <p>So this one is deliberately inert. It is set once, to the insert time, by
     * {@link #defaultEffectiveFrom()} when the caller has not set it, and the application never
     * touches it again. Re-dating it forward is a curator's decision that says "this rule is
     * materially different, assess projects against it again", and it is the only thing that
     * makes an already-assessed project stale.
     *
     * <p>Two cases worth stating because they are easy to get wrong. Reactivating a rule by
     * flipping {@link #active} back to true does not by itself re-date it, so a rule switched
     * off and on again reaches only projects that were never assessed; move this forward as
     * well if the intent is that everyone re-assesses. And a rule loaded with a back-dated
     * {@code effectiveFrom} reaches nobody who has already been assessed, which is the right
     * behaviour for recording history and the wrong one for a rule that is new to us.
     */
    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    /**
     * Gives a new rule an {@code effectiveFrom} of now unless one was supplied. A rule the
     * curator says nothing about is in force from when we learned of it, which is the only
     * defensible default: dating it earlier would hide it from every project already assessed,
     * and that is the exact failure this column exists to prevent.
     */
    @PrePersist
    void defaultEffectiveFrom() {
        if (effectiveFrom == null) {
            effectiveFrom = LocalDateTime.now();
        }
    }
}
