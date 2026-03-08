package org.tornotron.echno_backend.vendor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VendorPaymentTermsRepository extends JpaRepository<VendorPaymentTerms, Long> {

    Optional<VendorPaymentTerms> findByVendor_Id(Long vendorId);
}
