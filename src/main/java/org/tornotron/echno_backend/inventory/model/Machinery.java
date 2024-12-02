package org.tornotron.echno_backend.inventory.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.inventory.enums.MachineryStatus;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class Machinery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String model;
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    private MachineryStatus status;

    @ManyToOne
    private Site currentLocation;

    @OneToMany(mappedBy = "machinery")
    private List<MachineryTransfer> transfers;

}
