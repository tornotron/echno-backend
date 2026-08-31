package org.tornotron.echno_backend.siteTransferItem;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;

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

    @ManyToOne
    private Material material;

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
}
