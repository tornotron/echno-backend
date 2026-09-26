package org.tornotron.echno_backend.modules.sitenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One site note: a short written record an employee leaves for a project on a given day.
 *
 * <p>The aggregate of the module. Tenant scoped behind {@code orgFilter}. The project and the
 * author are plain ids with foreign keys in the changelog, not JPA associations, so the module
 * stays additive and reads nothing of the core's it does not need. The optional photo is not a
 * column: it rides on the platform's attachment store, keyed on the note's id.
 */
@Entity
@Table(name = "site_note",
        indexes = {
                @Index(name = "idx_site_note_organization", columnList = "organization_id"),
                @Index(name = "idx_site_note_project_date", columnList = "organization_id, project_id, note_date")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class SiteNote implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "note_date", nullable = false)
    private LocalDate noteDate;

    @Column(name = "author_employee_id", nullable = false)
    private Long authorEmployeeId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;
}
