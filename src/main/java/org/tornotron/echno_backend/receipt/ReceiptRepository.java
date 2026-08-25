package org.tornotron.echno_backend.receipt;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    Optional<Receipt> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Paginated receipt search. Every filter is optional (a null argument disables that
     * clause); the tenant orgFilter still applies. {@code search} matches the receipt
     * number or the received-from name, case-insensitively. Status matches the plain
     * string column.
     *
     * <p>The caller passes an already lower-cased {@code %...%} pattern for {@code search},
     * so the query never assembles it with SQL {@code CONCAT}/{@code ||}: on the null path
     * CockroachDB plans {@code <bytes> || <string>} and fails, so the pattern is built in
     * the service instead and matched here with a plain {@code LIKE} guarded by an
     * {@code IS NULL} check.
     */
    @Query("""
            SELECT r FROM Receipt r WHERE
              (:search IS NULL
                 OR LOWER(r.receiptNumber) LIKE :search
                 OR LOWER(r.receivedFrom) LIKE :search) AND
              (:status IS NULL OR r.status = :status)
            """)
    Page<Receipt> search(
            @Param("search") String search,
            @Param("status") String status,
            Pageable pageable);
}
