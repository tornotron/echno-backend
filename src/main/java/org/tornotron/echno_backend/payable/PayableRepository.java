package org.tornotron.echno_backend.payable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PayableRepository extends JpaRepository<Payable, Long> {

    Optional<Payable> findByIdAndOrganization_Id(Long id, Long organizationId);

    Optional<Payable> findByPayableNumber(String payableNumber);

    List<Payable> findByVendorIdAndOrganization_id(Long vendorId, Long organizationId);

    @Query("SELECT p FROM Payable p WHERE p.amountRecorded > p.amountPaid")
    List<Payable> findOutstandingPayables();

    boolean existsByPayableNumberAndOrganization_Id(String payableNumber, Long organizationId);
}
