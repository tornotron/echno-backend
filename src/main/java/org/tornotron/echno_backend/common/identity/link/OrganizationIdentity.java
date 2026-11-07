package org.tornotron.echno_backend.common.identity.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One organization at a provider, and the Echno organization it is. */
@Entity
@Table(name = "organization_identity")
@Getter
@NoArgsConstructor
public class OrganizationIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "provider_org_id", nullable = false)
    private String providerOrgId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
}
