package org.tornotron.echno_backend.organization;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dataset-consent column against the real schema (#790).
 *
 * <p>Two facts the export job depends on: an organization starts without consent, including
 * one persisted by code that never mentions the flag, and consent once recorded is what the
 * consenting-organizations query returns. The first fails without changeset 110-01 (no
 * column, the insert fails) and the second fails without the derived query.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrganizationDatasetConsentIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private OrganizationRepository repository;

    @BeforeEach
    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void anOrganizationStartsWithoutConsent() {
        Organization org = persistOrganization("Consent Default Org");
        entityManager.flush();
        entityManager.clear();

        Organization reloaded = repository.findById(org.getId()).orElseThrow();
        assertThat(reloaded.isDatasetConsent()).isFalse();
        Boolean stored = (Boolean) entityManager.createNativeQuery(
                        "SELECT dataset_consent FROM organization WHERE id = :id")
                .setParameter("id", org.getId()).getSingleResult();
        assertThat(stored).isFalse();
    }

    @Test
    void consentOnceRecordedIsWhatTheConsentingQueryReturns() {
        Organization consenting = persistOrganization("Consenting Org");
        Organization silent = persistOrganization("Silent Org");
        consenting.setDatasetConsent(true);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findById(consenting.getId()).orElseThrow().isDatasetConsent()).isTrue();
        assertThat(repository.findByDatasetConsentTrue())
                .extracting(Organization::getId)
                .contains(consenting.getId())
                .doesNotContain(silent.getId());
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }
}
