package org.tornotron.echno_backend.inventory.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.inventory.enums.TransferStatus;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
public class MachineryTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Machinery machinery;

    @ManyToOne
    private Site fromLocation;

    @ManyToOne
    private Site toLocation;

    private LocalDateTime transferDate;
    private LocalDateTime requestDate;
    private LocalDateTime deliveryDate;
    private LocalDateTime returnDate;

    @Enumerated(EnumType.STRING)
    private TransferStatus status;

    @ManyToOne
    private ProjectManager requestedBy;

    @ManyToOne
    private TransportCoordinator transportedBy;

    @ManyToOne
    private StoreManager approvedBy;

    @ManyToOne
    private Site site;

}
