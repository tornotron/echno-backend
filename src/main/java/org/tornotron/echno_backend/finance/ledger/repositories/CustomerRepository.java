package org.tornotron.echno_backend.finance.ledger.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads.
     */
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findScopedById(@Param("id") UUID id);

    Optional<Customer> findByCode(String code);
    boolean existsByCode(String code);
    Page<Customer> findByNameContainingIgnoreCaseAndActive(String name, boolean active, Pageable pageable);
}