package org.tornotron.echno_backend.vendor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface VendorContactRepository extends JpaRepository<VendorContact, Long> {

    List<VendorContact> findByVendor_Id(Long vendorId);

}
