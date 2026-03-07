package org.tornotron.echno_backend.vendor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

import javax.swing.text.html.Option;
import java.util.List;
import java.util.Optional;

public interface VendorRepository extends JpaRepository<Vendor, Long> {

    Optional<Vendor> findByVendorEmail(String vendorEmail);

    List<Vendor> findByVendorNameContainingIgnoreCase(String vendorName);

    boolean existsByVendorEmail(String vendorEmail);

    Optional<Vendor> findByIdAndOrganization_Id(Long id, Long organizationId);
    
    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    List<Vendor> findAllByStatus(VendorStatus status);

    List<Vendor> findAllByType(VendorType type);

}
