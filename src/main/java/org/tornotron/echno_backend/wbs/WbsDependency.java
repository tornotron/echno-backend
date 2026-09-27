package org.tornotron.echno_backend.wbs;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.wbs.enums.WbsDependencyType;

import java.time.LocalDateTime;

/**
 * A link between two activities of one project's schedule: the successor depends on the
 * predecessor by {@link #type} with {@link #lagDays} of lag.
 *
 * <p>Recorded information only. No planned date moves because of a link; a delay on the
 * predecessor shows as the predecessor's forecast finish, and the successor's dates stay as
 * agreed until someone changes them. The rows go with either activity when it is deleted
 * ({@code ON DELETE CASCADE} in the changelog).
 */
@Entity
@Table(name = "wbs_dependency", uniqueConstraints = {
        @UniqueConstraint(name = "uq_wbs_dependency_pair", columnNames = {"predecessor_id", "successor_id"})
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class WbsDependency implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "predecessor_id", nullable = false)
    private WbsElement predecessor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "successor_id", nullable = false)
    private WbsElement successor;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 2)
    private WbsDependencyType type = WbsDependencyType.FS;

    @Column(name = "lag_days", nullable = false)
    private Integer lagDays = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
