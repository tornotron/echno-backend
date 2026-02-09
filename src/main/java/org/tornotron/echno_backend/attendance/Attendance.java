package org.tornotron.echno_backend.attendance;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.attendance.enums.RecordType;
import org.tornotron.echno_backend.common.embed.GeoLocation;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@Table(name = "Attendance", indexes = {
    @Index(name = "idx_employee_timestamp", columnList = "employeeId, timestamp"),
    @Index(name = "idx_employee_id", columnList = "employeeId"),
    @Index(name = "idx_timestamp", columnList = "timestamp"),
    @Index(name = "idx_record_type", columnList = "recordType")
})
@FilterDef(name = "orgFilter", parameters = @ParamDef(name = "organizationId", type = Long.class))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Attendance implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long employeeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(nullable = false)
    private String location;

    @Enumerated(EnumType.STRING)
    private RecordType recordType;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = true)
    private String source;

    @Embedded
    private GeoLocation geoLocation;

    @Column(columnDefinition = "jsonb")
    private String deviceInfo;

    @Column
    private LocalDateTime lastModifiedAt;

    @Column
    private String modifiedBy;

    @Column(length = 500)
    private String correctionReason;
}
