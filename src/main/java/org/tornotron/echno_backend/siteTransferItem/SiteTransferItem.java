package org.tornotron.echno_backend.siteTransferItem;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;

@Data
@Entity
@NoArgsConstructor
@FilterDef(name = "orgFilter", parameters = @ParamDef(name = "organizationId", type = Long.class))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class SiteTransferItem implements TenantScopedEntity {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
