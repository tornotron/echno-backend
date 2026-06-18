package org.tornotron.echno_backend.finance.bank.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.finance.bank.domain.CompanyBankAccount;

import java.util.List;
import java.util.UUID;

@Repository
public interface CompanyBankAccountRepository extends JpaRepository<CompanyBankAccount, UUID> {
    List<CompanyBankAccount> findByActiveTrue();
}
