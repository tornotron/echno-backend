package org.tornotron.echno_backend.expense;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    Optional<Expense> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Paginated expense search. Every filter is optional (a null argument disables that
     * clause); the tenant orgFilter still applies. {@code search} matches the expense
     * number or description, case-insensitively. Status matches the plain string column.
     *
     * <p>The caller passes an already lower-cased {@code %...%} pattern for {@code search},
     * so the query never assembles it with SQL {@code CONCAT}/{@code ||}: on the null path
     * CockroachDB plans {@code <bytes> || <string>} and fails, so the pattern is built in
     * the service instead and matched here with a plain {@code LIKE} guarded by an
     * {@code IS NULL} check.
     */
    @Query("""
            SELECT e FROM Expense e WHERE
              (:search IS NULL
                 OR LOWER(e.expenseNumber) LIKE :search
                 OR LOWER(e.description) LIKE :search) AND
              (:status IS NULL OR e.status = :status)
            """)
    Page<Expense> search(
            @Param("search") String search,
            @Param("status") String status,
            Pageable pageable);
}
