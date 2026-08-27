package org.tornotron.echno_backend.inspection.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.inspection.DefectSeverity;
import org.tornotron.echno_backend.inspection.DefectStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A defect raised during an inspection. Owned by {@link Inspection} through the
 * {@code inspection_id} FK; the parent cascades persist and removal. Severity and
 * status are enums whose wire values are the same string unions the web contract
 * already documents ('critical' | 'major' | 'minor' and 'open' | 'in-progress' |
 * 'resolved' | 'verified'), so the payload is unchanged from when they were text.
 */
@Entity
@Table(name = "inspection_defects",
        indexes = @Index(name = "idx_insp_defect_inspection", columnList = "inspection_id"))
@Getter
@Setter
@NoArgsConstructor
public class InspectionDefect {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false)
    private Inspection inspection;

    @Column(length = 200)
    private String category;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    private DefectSeverity severity;

    @Column(length = 300)
    private String location;

    @ElementCollection
    @CollectionTable(name = "inspection_defect_photos",
            joinColumns = @JoinColumn(name = "defect_id"))
    @Column(name = "photo", length = 500)
    private List<String> photos = new ArrayList<>();

    @Column(name = "corrective_action", nullable = false, length = 1000)
    private String correctiveAction;

    @Column(name = "responsible_party", length = 200)
    private String responsibleParty;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private DefectStatus status = DefectStatus.OPEN;

    @Column(name = "resolved_date")
    private LocalDate resolvedDate;

    @Column(name = "line_order", nullable = false)
    private int lineOrder;
}
