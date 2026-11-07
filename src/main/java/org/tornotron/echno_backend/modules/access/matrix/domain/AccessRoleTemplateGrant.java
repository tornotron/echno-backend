package org.tornotron.echno_backend.modules.access.matrix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.common.module.GlobalReferenceData;
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/** One permission a starting role grants, with its scope. An owned child of its template. */
@Entity
@GlobalReferenceData("One grant of a starting role, which is the platform's own list")
@Table(name = "access_role_template_grant",
        uniqueConstraints = @UniqueConstraint(name = "uq_access_role_template_grant",
                columnNames = {"template_id", "permission_key", "scope"}))
@Getter
@Setter
@NoArgsConstructor
public class AccessRoleTemplateGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private AccessRoleTemplate template;

    @Column(name = "permission_key", nullable = false, length = 120)
    private String permissionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessScope scope;
}
