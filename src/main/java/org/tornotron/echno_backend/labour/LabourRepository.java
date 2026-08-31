package org.tornotron.echno_backend.labour;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LabourRepository extends JpaRepository<Labour,Long> {

    Optional<Labour> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Whether another worker in this organization already holds the given email. The id is
     * excluded so an update that resends a worker's own address does not clash with itself.
     *
     * <p>Scoped to the organization because the constraint behind it, {@code uk_labour_org_email},
     * is: the same person may be on two contractors' books.
     */
    boolean existsByEmailAndOrganization_IdAndIdNot(String email, Long organizationId, Long id);

    /** As {@link #existsByEmailAndOrganization_IdAndIdNot}, for {@code uk_labour_org_phone_number}. */
    boolean existsByPhoneNumberAndOrganization_IdAndIdNot(String phoneNumber, Long organizationId, Long id);

    /** As {@link #existsByEmailAndOrganization_IdAndIdNot}, for {@code uk_labour_org_bank_account_number}. */
    boolean existsByBankAccountNumberAndOrganization_IdAndIdNot(String bankAccountNumber, Long organizationId, Long id);
}
