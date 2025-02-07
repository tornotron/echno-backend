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
@Table(name = "Attendance")
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
}
