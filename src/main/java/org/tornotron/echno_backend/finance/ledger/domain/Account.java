package org.tornotron.echno_backend.finance.ledger.domain;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.organization.Organization;

import java.util.UUID;

/**
 * A single account in the chart of accounts, the ledger's classification of what money is or does.
 *
 * <p>Accounts form a self-referential tree through {@code parent}, and a child shares its parent's
 * {@link AccountType}. Only leaf accounts receive journal lines; parent (header) accounts serve as
 * roll-up buckets in reports. The code is unique per organization, and an account is deactivated
 * rather than deleted so its posting history survives.
 */
@Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_accounts_code", columnNames = {"organization_id", "code"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class Account extends BaseEntity implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Account parent;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

}

