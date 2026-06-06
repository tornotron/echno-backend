package org.tornotron.echno_backend.finance.ledger.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByCode(String code);
    boolean existsByCode(String code);
    Page<Customer> findByNameContainingIgnoreCaseAndActive(String name, boolean active, Pageable pageable);
}