package org.tornotron.echno_backend.siteTransferItem;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.asset.Asset;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferLineType;

@Data
@Entity
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class SiteTransferItem implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private SiteTransfer siteTransfer;

    /**
     * Whether the line carries a material or an asset. A material line names {@link #material}
     * and a quantity; an asset line names {@link #asset} and always sends one unit.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 20)
    private SiteTransferLineType lineType = SiteTransferLineType.MATERIAL;

    @ManyToOne
    private Material material;

    /** The asset this line moves. Set only on an {@link SiteTransferLineType#ASSET} line. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    /**
     * Set while this asset line is on a transfer that is in transit and the asset has not been
     * recorded as arriving. A unique index over the set rows is what keeps an asset on at most
     * one open transfer. Cleared when the asset is received, or when the transfer is cancelled
     * or reversed. Always false on a material line.
     */
    @Column(name = "asset_in_transit", nullable = false)
    private boolean assetInTransit;

    @Column(name = "sent_quantity")
    private Integer sentQuantity;

    /**
     * How much of {@link #sentQuantity} has been recorded as arriving at the receiving site.
     *
     * <p>Null until somebody records a receipt, which is the state a transfer in transit is in:
     * nothing has been said about this line yet, which is different from saying nothing arrived.
     * A transfer between two stores on one project is written received in full at creation,
     * because the material never leaves that site's custody and there is no arrival to confirm.
     *
     * <p>The gap between this and {@code sentQuantity} on a transfer that has been received is
     * an open variance, not a loss: the transfer records the shortfall and leaves closing it to
     * a stock adjustment, because writing a loss movement of its own would be a stock correction
     * nobody authorised.
     */
    @Column(name = "received_quantity")
    private Integer receivedQuantity;

    @Column(name = "remarks")
    private String remarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    /** Whether this line moves an asset rather than a quantity of a material. */
    public boolean isAssetLine() {
        return lineType == SiteTransferLineType.ASSET;
    }
}
