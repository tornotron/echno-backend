package org.tornotron.echno_backend.labour;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.labour.dto.LabourCreationDto;
import org.tornotron.echno_backend.labour.dto.LabourUpdateDto;
import org.tornotron.echno_backend.labour.mapper.LabourMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for the scope of a worker's contact details against a real CockroachDB, so
 * the schema's own constraints are part of what is under test (issue #614).
 *
 * <p>A worker's email, phone number and bank account number used to be reserved across every
 * organization at once, under {@code uq_labour_email}, {@code uq_labour_phone_number} and
 * {@code uq_labour_bank_account_number}. A labourer on two contractors' books is an ordinary
 * arrangement on a construction site, and the second contractor could not register them at all:
 * nothing in {@link LabourService} checked for a duplicate, so the create came back as a
 * constraint violation over rows they cannot see.
 *
 * <p>Migration 076 makes all three per organization, and the service now says so before the
 * insert reaches them, so a genuine clash within one tenant names the detail that clashed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LabourService.class, LabourMapperImpl.class, TenantEntityHelper.class})
class LabourUniquenessIT extends AbstractIntegrationTest {

    private static final String SHARED_EMAIL = "suresh.pillai@example.test";
    private static final String SHARED_PHONE = "9847098470";
    private static final String SHARED_ACCOUNT = "50100234567890";

    @Autowired
    private LabourService labourService;

    @PersistenceContext
    private EntityManager entityManager;

    private Tenant first;
    private Tenant second;

    /** One organization with the project a labour record has to reference. */
    private record Tenant(Long orgId, Long projectId) {
    }

    @BeforeEach
    void seed() {
        first = persistTenant("first");
        second = persistTenant("second");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private Tenant persistTenant(String name) {
        Organization org = new Organization();
        org.setOrganizationName("Labour Uniqueness Org " + name);
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("labour-uniqueness-" + name + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);

        Project project = new Project();
        project.setProjectName("Project " + name);
        project.setOrganization(org);
        entityManager.persist(project);

        entityManager.flush();
        return new Tenant(org.getId(), project.getId());
    }

    private LabourCreationDto dtoFor(Tenant tenant, String labourId, String email,
                                     String phone, String account) {
        LabourCreationDto dto = new LabourCreationDto();
        dto.setLabourID(labourId);
        dto.setFullName("Suresh Pillai");
        dto.setEmail(email);
        dto.setPhoneNumber(phone);
        dto.setEmploymentType("DAILY_WAGE");
        dto.setSkillLevel("SKILLED");
        dto.setStatus("ACTIVE");
        dto.setJoiningDate(LocalDate.of(2026, 1, 15));
        dto.setCurrentProjectId(tenant.projectId());
        dto.setBankAccountNumber(account);
        return dto;
    }

    private Long createAs(Tenant tenant, String labourId, String email, String phone, String account) {
        TenantContext.setCurrentOrgId(tenant.orgId());
        labourService.createLabour(dtoFor(tenant, labourId, email, phone, account));
        entityManager.flush();
        return entityManager
                .createQuery("SELECT l.id FROM Labour l WHERE l.labourID = :code", Long.class)
                .setParameter("code", labourId)
                .getSingleResult();
    }

    // --- The same worker on two contractors' books ------------------------

    @Test
    void sameEmailInAnotherOrganization_isAccepted() {
        createAs(first, "LAB-0001", SHARED_EMAIL, "9000000001", null);

        assertThatNoException().isThrownBy(() ->
                createAs(second, "LAB-0002", SHARED_EMAIL, "9000000002", null));
    }

    @Test
    void samePhoneNumberInAnotherOrganization_isAccepted() {
        createAs(first, "LAB-0003", null, SHARED_PHONE, null);

        assertThatNoException().isThrownBy(() ->
                createAs(second, "LAB-0004", null, SHARED_PHONE, null));
    }

    @Test
    void sameBankAccountNumberInAnotherOrganization_isAccepted() {
        createAs(first, "LAB-0005", null, "9000000005", SHARED_ACCOUNT);

        assertThatNoException().isThrownBy(() ->
                createAs(second, "LAB-0006", null, "9000000006", SHARED_ACCOUNT));
    }

    // --- A genuine clash within one organization --------------------------

    @Test
    void sameEmailTwiceInOneOrganization_isRejectedAsADuplicate() {
        createAs(first, "LAB-0007", SHARED_EMAIL, "9000000007", null);

        assertThatExceptionOfType(DuplicateResourceException.class).isThrownBy(() ->
                createAs(first, "LAB-0008", SHARED_EMAIL, "9000000008", null));
    }

    @Test
    void samePhoneNumberTwiceInOneOrganization_isRejectedAsADuplicate() {
        createAs(first, "LAB-0009", null, SHARED_PHONE, null);

        assertThatExceptionOfType(DuplicateResourceException.class).isThrownBy(() ->
                createAs(first, "LAB-0010", null, SHARED_PHONE, null));
    }

    @Test
    void sameBankAccountNumberTwiceInOneOrganization_isRejectedAsADuplicate() {
        createAs(first, "LAB-0011", null, "9000000011", SHARED_ACCOUNT);

        assertThatExceptionOfType(DuplicateResourceException.class).isThrownBy(() ->
                createAs(first, "LAB-0012", null, "9000000012", SHARED_ACCOUNT));
    }

    // --- Absent details are not duplicates of one another -----------------

    @Test
    void twoWorkersWithNoEmailOrBankAccount_areBothAccepted() {
        createAs(first, "LAB-0013", null, "9000000013", null);

        assertThatNoException().isThrownBy(() ->
                createAs(first, "LAB-0014", null, "9000000014", null));
    }

    // --- Updating a worker with their own details -------------------------

    @Test
    void updatingAWorkerWithTheirOwnDetails_isAccepted() {
        Long id = createAs(first, "LAB-0015", SHARED_EMAIL, SHARED_PHONE, SHARED_ACCOUNT);

        LabourUpdateDto updates = new LabourUpdateDto();
        updates.setEmail(SHARED_EMAIL);
        updates.setPhoneNumber(SHARED_PHONE);
        updates.setBankAccountNumber(SHARED_ACCOUNT);
        updates.setFullName("Suresh K Pillai");

        assertThatNoException().isThrownBy(() -> {
            labourService.partialUpdateALabour(updates, id);
            entityManager.flush();
        });
    }

    @Test
    void updatingAWorkerOntoAnotherWorkersEmail_isRejectedAsADuplicate() {
        createAs(first, "LAB-0016", SHARED_EMAIL, "9000000016", null);
        Long id = createAs(first, "LAB-0017", "other@example.test", "9000000017", null);

        LabourUpdateDto updates = new LabourUpdateDto();
        updates.setEmail(SHARED_EMAIL);

        assertThatExceptionOfType(DuplicateResourceException.class).isThrownBy(() ->
                labourService.partialUpdateALabour(updates, id));
    }
}
