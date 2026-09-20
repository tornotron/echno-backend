package org.tornotron.echno_backend.holiday;

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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One declared holiday of an organization: a date on which nobody is expected at work and which
 * a leave request is therefore not charged for, subject to the policy's weekend and holiday
 * treatment.
 *
 * <p>Organization-wide only in this release. A region or site scope is the obvious next column,
 * and the unique key on organization and date is what would have to change with it; nothing
 * else here assumes there is only one calendar per organization.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "holiday", indexes = {
        @Index(name = "idx_holiday_org_date", columnList = "organization_id, holiday_date")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_holiday_org_date", columnNames = {"organization_id", "holiday_date"})
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Holiday implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
