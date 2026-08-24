package org.tornotron.echno_backend.finance.posting.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.organization.Organization;

import java.util.UUID;

/**
 * A per-organization override that points a {@link PostingRole} at a concrete account.
 *
 * <p>Where a mapping row exists for a role, the finance postings use its account instead of the
 * code configured on the posting properties. At most one row exists per organization and role. The
 * mapping targets the account by id, so renaming or renumbering the account leaves the posting intact.
 */
@Entity
@Table(name = "posting_account_mapping",
        uniqueConstraints = @UniqueConstraint(name = "uk_posting_account_mapping_role",
                columnNames = {"organization_id", "role"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class PostingAccountMapping implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PostingRole role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;
}
