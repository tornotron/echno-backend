package org.tornotron.echno_backend.attendance;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.attendance.enums.RecordType;
import org.tornotron.echno_backend.common.embed.GeoLocation;

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
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long employeeId;

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
