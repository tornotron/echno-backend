package org.tornotron.echno_backend.leave;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@Table(name = "leave_request_sequence", uniqueConstraints = {
        @UniqueConstraint(name = "uk_leave_req_seq_org_year", columnNames = {"organization_id", "year"})
})
public class LeaveRequestSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence = 0L;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
