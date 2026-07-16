package org.tornotron.echno_backend.finance.bank.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.finance.bank.domain.CompanyBankAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyBankAccountRepository extends JpaRepository<CompanyBankAccount, UUID> {
    List<CompanyBankAccount> findByActiveTrue();

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads.
     */
    @Query("SELECT c FROM CompanyBankAccount c WHERE c.id = :id")
    Optional<CompanyBankAccount> findScopedById(@Param("id") UUID id);
}
