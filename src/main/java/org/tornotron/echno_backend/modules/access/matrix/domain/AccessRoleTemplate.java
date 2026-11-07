package org.tornotron.echno_backend.modules.access.matrix.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.common.module.GlobalReferenceData;

/**
 * A starting role, copied into each organization when it is provisioned.
 *
 * <p>Changing the starting matrix is a data change to these rows. An organization's own roles are
 * {@link AccessRole} rows and do not change when a template does.
 */
@Entity
@Table(name = "access_role_template")
@GlobalReferenceData("The starting roles are the platform's own list, copied into every organization")
@Getter
@Setter
@NoArgsConstructor
public class AccessRoleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 120)
    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "is_baseline", nullable = false)
    private boolean baseline;

    @Column(name = "legacy_org_role", length = 60)
    private String legacyOrgRole;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AccessRoleTemplateGrant> grants = new ArrayList<>();
}
