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
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/**
 * One permission a role grants, with the scope it covers. An owned child of {@link AccessRole}:
 * it has no organization column of its own and cannot exist without its role.
 */
@Entity
@Table(name = "access_role_grant",
        uniqueConstraints = @UniqueConstraint(name = "uq_access_role_grant",
                columnNames = {"role_id", "permission_key", "scope"}))
@Getter
@Setter
@NoArgsConstructor
public class AccessRoleGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private AccessRole role;

    @Column(name = "permission_key", nullable = false, length = 120)
    private String permissionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessScope scope;

    // A baseline grant every member keeps; the role cannot drop it.
    @Column(name = "is_locked", nullable = false)
    private boolean locked;
}
