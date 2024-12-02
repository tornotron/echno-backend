package org.tornotron.echno_backend.inventory.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String siteName;
    private String address;

    @OneToMany(mappedBy = "currentLocation")
    private List<Machinery> machineriesAtSite;

    @OneToMany(mappedBy = "site")
    private List<MachineryTransfer> transfers;

}
