package org.tornotron.echno_backend.modules.vision.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * Whether an organization runs its models on every new evidence image, and which model families.
 * One row per organization, keyed by it; no row reads as auto-inference off. An empty
 * {@code modelKeys} means every family that has an ACTIVE version.
 */
@Entity
@Table(name = "cv_org_settings")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class VisionOrgSettings implements TenantScopedEntity {

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", insertable = false, updatable = false)
    private Organization organization;

    @Column(name = "auto_infer", nullable = false)
    private boolean autoInfer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_keys", nullable = false, columnDefinition = "jsonb")
    private List<String> modelKeys = new ArrayList<>();

    @Column(name = "updated_by_id")
    private Long updatedById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Override
    public void setOrganization(Organization organization) {
        this.organization = organization;
        this.organizationId = organization == null ? null : organization.getId();
    }
}
