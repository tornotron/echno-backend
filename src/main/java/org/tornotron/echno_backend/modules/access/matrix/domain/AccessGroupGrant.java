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
 * One permission a group grants its members directly, with the scope it covers. An owned child
 * of {@link AccessGroup}.
 */
@Entity
@Table(name = "access_group_grant",
        uniqueConstraints = @UniqueConstraint(name = "uq_access_group_grant",
                columnNames = {"group_id", "permission_key", "scope"}))
@Getter
@Setter
@NoArgsConstructor
public class AccessGroupGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private AccessGroup group;

    @Column(name = "permission_key", nullable = false, length = 120)
    private String permissionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessScope scope;
}
