package org.tornotron.echno_backend.vendor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VendorBankAccountRepository extends JpaRepository<VendorBankAccount, Long> {

    List<VendorBankAccount> findByVendor_Id(Long vendorId);
}
