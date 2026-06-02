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
    Optional<Account> findByCode(String code);
    List<Account> findByType(AccountType type);
    List<Account> findByActiveTrue();
    boolean existsByCode(String code);

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
