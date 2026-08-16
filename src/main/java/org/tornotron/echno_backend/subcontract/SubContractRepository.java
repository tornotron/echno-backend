package org.tornotron.echno_backend.subcontract;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SubContractRepository extends JpaRepository<SubContract, Long> {

    Optional<SubContract> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByContractIdAndOrganization_Id(String contractId, Long organizationId);

    /**
     * Paginated subcontract search. Every filter is optional (a null argument disables
     * that clause); the tenant orgFilter still applies. {@code search} matches the
     * contract name, contractor name, or business contract id, case-insensitively.
     * Type and status match the plain string columns.
     */
    @Query("""
            SELECT s FROM SubContract s WHERE
              (:search IS NULL
                 OR LOWER(s.contractName) LIKE :search
                 OR LOWER(s.contractorName) LIKE :search
                 OR LOWER(s.contractId) LIKE :search) AND
              (:status IS NULL OR s.status = :status) AND
              (:type IS NULL OR s.type = :type)
            """)
    Page<SubContract> search(
            @Param("search") String search,
            @Param("status") String status,
            @Param("type") String type,
            Pageable pageable);
}
