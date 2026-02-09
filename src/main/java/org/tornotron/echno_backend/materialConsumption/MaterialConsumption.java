package org.tornotron.echno_backend.materialConsumption;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class MaterialConsumption implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumption_date", nullable = false)
    private LocalDateTime consumptionDate;

    @ManyToOne
    private Material material;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumption_type", nullable = false)
    private MaterialConsumptionType consumptionType;

    private String details;

    @ManyToOne
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

}
