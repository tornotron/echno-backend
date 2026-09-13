package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.billing.gateway.BillingCustomer;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.util.Optional;

public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, Long> {

    Optional<BillingCustomer> findByOrganizationIdAndProvider(Long organizationId, ProviderId provider);

    Optional<BillingCustomer> findByProviderAndProviderCustomerId(ProviderId provider, String providerCustomerId);
}
