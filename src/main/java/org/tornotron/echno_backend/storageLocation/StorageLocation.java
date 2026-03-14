package org.tornotron.echno_backend.storageLocation;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

@Data
@Entity
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class StorageLocation implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "location_name", nullable = false)
    private String locationName;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false)
    private StorageLocationType locationType;

    private String address;

    private boolean isActive = true;

    private String capacity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
