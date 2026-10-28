package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.time.LocalDateTime;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessPartyLink;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

/**
 * Party links against a real CockroachDB: a person's customer and vendor links load inside the
 * organization, and the table refuses a row naming both or neither.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccessPartyLinkRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private AccessPartyLinkRepository links;

    @Autowired
    private TestEntityManager em;

    @AfterEach
    void disableFilter() {
        em.getEntityManager().unwrap(Session.class).disableFilter("orgFilter");
    }

    @Test
    void aPersonsCustomerAndVendorLinksLoad() {
        Organization org = persistOrganization("Party Org A");
        Employee contact = persistEmployee(org, "party-a");
        Customer customer = persistCustomer(org, "C-001");
        Vendor vendor = persistVendor(org, "Acme Formwork");
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
        Organization org = persistOrganization("Party Org B");
        Employee contact = persistEmployee(org, "party-b");
        Customer customer = persistCustomer(org, "C-002");
        Vendor vendor = persistVendor(org, "Beta Steel");
        em.flush();

        assertThatThrownBy(() -> em.getEntityManager().createNativeQuery(
                        "INSERT INTO access_party_link (id, organization_id, employee_id, customer_id, vendor_id, created_at) "
                                + "VALUES (gen_random_uuid(), ?1, ?2, ?3, ?4, now())")
                .setParameter(1, org.getId())
                .setParameter(2, contact.getId())
                .setParameter(3, customer.getId())
                .setParameter(4, vendor.getId())
                .executeUpdate())
                .rootCause()
                .hasMessageContaining("CHECK constraint");
    }

    private void scopeTo(Organization org) {
        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter").setParameter("organizationId", org.getId());
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private Employee persistEmployee(Organization org, String name) {
        User user = new User();
        user.setKeycloakId("kc-" + name);
        user.setName(name);
        em.persist(user);
        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(employee);
        return employee;
    }

    private Customer persistCustomer(Organization org, String code) {
        Customer customer = new Customer();
        customer.setCode(code);
        customer.setName("Customer " + code);
        customer.setActive(true);
        customer.setOrganization(org);
        // This slice runs without JPA auditing, which normally stamps the finance base entity.
        customer.setCreatedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());
        em.persist(customer);
        return customer;
    }

    private Vendor persistVendor(Organization org, String name) {
        Vendor vendor = new Vendor();
        vendor.setVendorName(name);
        vendor.setType(VendorType.MATERIALS);
        vendor.setStatus(VendorStatus.ACTIVE);
        vendor.setVendorEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        vendor.setOrganization(org);
        em.persist(vendor);
        return vendor;
    }
}
