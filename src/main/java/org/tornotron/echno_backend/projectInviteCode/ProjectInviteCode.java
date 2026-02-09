package org.tornotron.echno_backend.projectInviteCode;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Represents a project invite code entity in the system.
 * This code allows users to join a specific organization.
 */
@Entity
@Data
@FilterDef(name = "orgFilter", parameters = @ParamDef(name = "organizationId", type = Long.class))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class ProjectInviteCode implements TenantScopedEntity {

    /** The unique identifier for the invite code. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The unique five-digit code used for the invitation. */
    @Column(nullable = false,unique = true)
    private int code;

    /** The organization to which this invite code belongs. */
    @ManyToOne
    private Organization organization;

    /** The date and time when this invite code expires. */
    @Column(nullable = false)
    private LocalDateTime expiryDate;

    /** A flag indicating whether the invite code is currently active. */
    @Column(nullable = false)
    private boolean isActive;

    /** The maximum number of times this invite code can be used. */
    @Column(nullable = false)
    private int maxUses;

    /** The current number of times this invite code has been used. */
    @Column(nullable = false)
    private int currentUses;

    /** A JSON map containing default details for the employee who joins using this code. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> employeeDetails;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

}