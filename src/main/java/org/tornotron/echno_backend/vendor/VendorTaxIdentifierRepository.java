package org.tornotron.echno_backend.vendor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VendorTaxIdentifierRepository extends JpaRepository<VendorTaxIdentifier, Long> {

    List<VendorTaxIdentifier> findByVendor_Id(Long vendorId);
}
