package org.tornotron.echno_backend.inventory.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class ProjectManager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;
    private String contactNumber;

    @OneToMany(mappedBy = "requestedBy")
    private List<MachineryRequest> requests;

    @OneToMany(mappedBy = "requestedBy")
    private List<MachineryTransfer> transfers;

}
