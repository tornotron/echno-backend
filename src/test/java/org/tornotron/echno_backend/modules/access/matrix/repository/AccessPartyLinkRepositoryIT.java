package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessPartyLink;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.vendor.Vendor;

/**
 * Party links against a real CockroachDB: a person's customer and vendor links load inside the
 * organization, and the table refuses a row naming both or neither.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Customer carries Spring Data's AuditingEntityListener, so this slice imports the auditing
// configuration for a handler of its own; without it the listener is configured by whichever earlier
// context last enabled auditing, and a persist fails once the context cache has closed that one.
@Import(JpaAuditingConfig.class)
class AccessPartyLinkRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private AccessPartyLinkRepository links;

    @Autowired
    private TestEntityManager em;

    private AccessFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new AccessFixtures(em.getEntityManager());
    }

    @AfterEach
    void disableFilter() {
        em.getEntityManager().unwrap(Session.class).disableFilter("orgFilter");
    }

    private void scopeTo(Organization org) {
        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter").setParameter("organizationId", org.getId());
    }

    @Test
    void aPersonsCustomerAndVendorLinksLoad() {
        Organization org = fixtures.organization("Party Org A");
        Employee contact = fixtures.employee(org, "party-a");
        Customer customer = fixtures.customer(org, "C-001");
        Vendor vendor = fixtures.vendor(org, "Acme Formwork");
        em.persist(AccessPartyLink.toCustomer(org, contact.getId(), customer.getId()));
        em.persist(AccessPartyLink.toVendor(org, contact.getId(), vendor.getId()));
        em.flush();
        em.clear();

        scopeTo(org);

        assertThat(links.findByEmployee(contact.getId()))
                .extracting(AccessPartyLink::getCustomerId, AccessPartyLink::getVendorId)
                .containsExactlyInAnyOrder(
                        tuple(customer.getId(), null),
                        tuple(null, vendor.getId()));
    }

    @Test
    void aLinkMustNameExactlyOneParty() {
        Organization org = fixtures.organization("Party Org B");
        Employee contact = fixtures.employee(org, "party-b");
        Customer customer = fixtures.customer(org, "C-002");
        Vendor vendor = fixtures.vendor(org, "Beta Steel");
        em.flush();

        assertThatThrownBy(() -> em.getEntityManager().createNativeQuery(
                        "INSERT INTO access_party_link (id, organization_id, employee_id, customer_id, vendor_id, created_at) "
                                + "VALUES (gen_random_uuid(), ?1, ?2, ?3, ?4, now())")
                .setParameter(1, org.getId())
                .setParameter(2, contact.getId())
                .setParameter(3, customer.getId())
                .setParameter(4, vendor.getId())
                .executeUpdate())
                .hasStackTraceContaining("CHECK constraint");
    }
}
