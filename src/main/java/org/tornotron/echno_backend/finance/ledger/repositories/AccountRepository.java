package org.tornotron.echno_backend.finance.ledger.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads.
     */
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findScopedById(@Param("id") UUID id);

    /**
     * Org-scoped lookup by account code. Codes are unique only within an organization, so
     * a code must always be resolved against the current tenant; a bare code lookup can
     * match another tenant's account of the same code.
     */
    Optional<Account> findByCodeAndOrganization_Id(String code, Long organizationId);
    List<Account> findByType(AccountType type);
    List<Account> findByActiveTrue();
    boolean existsByCode(String code);

    /**
     * Whether another account in the current tenant already uses the given code, excluding the
     * account with the given id. Used on edit so an account can keep its own code while a clash
     * with any other account is still rejected. The Hibernate {@code orgFilter} scopes it to the
     * current tenant.
     */
    boolean existsByCodeAndIdNot(String code, UUID id);

    /**
     * Whether the given organization already owns any account. Used by the
     * chart-of-accounts seeder to stay idempotent: it seeds only an org whose
     * chart is empty. Queries the organization id directly so it does not depend
     * on the Hibernate {@code orgFilter} being enabled.
     */
    boolean existsByOrganizationId(Long organizationId);

    /** Direct children of the given account, used to derive the next sibling code. */
    List<Account> findByParent(Account parent);

    /** Root accounts (no parent) of a given type, used to derive the next root code. */
    List<Account> findByParentIsNullAndType(AccountType type);

    /**
     * Of the given account ids, returns those that are used as a parent by at
     * least one other account — i.e. the header (non-leaf) accounts. Used to
     * reject journal postings to summary accounts in a single query.
     */
    @Query("select distinct a.parent.id from Account a where a.parent.id in :ids")
    List<UUID> findHeaderIdsAmong(@Param("ids") Collection<UUID> ids);
}
