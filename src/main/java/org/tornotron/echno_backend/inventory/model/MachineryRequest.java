package org.tornotron.echno_backend.inventory.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.inventory.enums.RequestStatus;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class MachineryRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime requestDate;

    @Enumerated(EnumType.STRING)
    private RequestStatus status;

    @ManyToOne
    private ProjectManager requestedBy;

    @ManyToOne
    private StoreManager approvedBy;

    @OneToMany
    private List<Machinery> requestedMachinery;

}
