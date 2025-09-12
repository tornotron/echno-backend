package org.tornotron.echno_backend.siteTransferItem;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;

@Data
@Entity
@NoArgsConstructor
public class SiteTransferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private SiteTransfer siteTransfer;

    @ManyToOne
    private Material material;

    @Column(name = "sent_quantity")
    private Integer sentQuantity;

    @Column(name = "remarks")
    private String remarks;
}
