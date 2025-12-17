package org.tornotron.echno_backend.vendor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VendorRepository extends JpaRepository<Vendor, Long> {

    Optional<Vendor> findByVendorEmail(String vendorEmail);

    List<Vendor> findByVendorNameContainingIgnoreCase(String vendorName);

    boolean existsByVendorEmail(String vendorEmail);
}
