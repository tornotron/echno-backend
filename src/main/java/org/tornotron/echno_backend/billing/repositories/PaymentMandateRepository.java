package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.billing.gateway.PaymentMandate;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.util.Optional;

public interface PaymentMandateRepository extends JpaRepository<PaymentMandate, Long> {

    Optional<PaymentMandate> findByProviderAndProviderMandateRef(ProviderId provider, String providerMandateRef);

    /** The organization's most recently registered mandate; what the checkout page shows. */
    Optional<PaymentMandate> findFirstByOrganization_IdOrderByCreatedAtDesc(Long organizationId);
}
