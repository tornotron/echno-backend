package org.tornotron.echno_backend.payable;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.payable.mapper.PayableMapperImpl;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for {@link PayableService#createPayable} against a real CockroachDB, so the
 * schema's own constraints are part of what is under test.
 *
 * <p>Two rules are pinned here. The payable number is the tenant's own reference series, so it is
 * unique within an organization and free to repeat across organizations; it used to be unique
 * across the whole estate, which turned a second tenant's ordinary numbering into a constraint
 * violation and told them a number was taken by a tenant they cannot see. And the opening paid
 * amount is held to the recorded amount, because nothing afterwards can bring an over-paid payable
 * back: recordPayment refuses every further payment and the module has no update or delete.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PayableService.class, PayableMapperImpl.class, EmployeeMapperImpl.class,
        AttachmentMapperImpl.class, TenantEntityHelper.class,
        org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapper.class})
class PayableCreationIT extends AbstractIntegrationTest {

    private static final String SHARED_NUMBER = "PAY-2026-000001";

    @Autowired
    private PayableService payableService;

    @PersistenceContext
    private EntityManager entityManager;

    // The attachment mapper in the payable mapper chain autowires this; nothing here
    // touches file storage, so a mock satisfies the graph.
    @MockitoBean
    private FileStorageService fileStorageService;

    private Tenant first;
    private Tenant second;

    /** One organization with the project and employee a payable has to reference. */
    private record Tenant(Long orgId, Long projectId, Long employeeId) {
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
        org.setOrganizationName("Payable Creation Org " + name);
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("payable-creation-" + name + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);

        Project project = new Project();
        project.setProjectName("Project " + name);
        project.setOrganization(org);
        entityManager.persist(project);

        User user = new User();
        user.setKeycloakId("kc-payable-creation-" + name);
        user.setName("Payable Creator " + name);
        entityManager.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName("Payable Creator " + name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress("payable-creator-" + name + "@example.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        entityManager.persist(employee);

        entityManager.flush();
        return new Tenant(org.getId(), project.getId(), employee.getId());
    }

    private PayableCreationDto dtoFor(Tenant tenant, String payableNumber,
                                      BigDecimal amountRecorded, BigDecimal amountPaid) {
        PayableCreationDto dto = new PayableCreationDto();
        dto.setPayableNumber(payableNumber);
        dto.setContractorName("ACME Contractors");
        dto.setContractType("MATERIAL_SUPPLY");
        dto.setAmountRecorded(amountRecorded);
        dto.setAmountPaid(amountPaid);
        dto.setProjectId(tenant.projectId());
        dto.setCreatedBy(tenant.employeeId());
        return dto;
    }

    private PayableDto createAs(Tenant tenant, String payableNumber,
                                BigDecimal amountRecorded, BigDecimal amountPaid) {
        TenantContext.setCurrentOrgId(tenant.orgId());
        return payableService.createPayable(dtoFor(tenant, payableNumber, amountRecorded, amountPaid));
    }

    // --- Payable number scope (issue #602) --------------------------------

    @Test
    void sameNumberInAnotherOrganization_isAccepted() {
        createAs(first, SHARED_NUMBER, new BigDecimal("1000.00"), null);

        assertThatNoException().isThrownBy(() ->
                createAs(second, SHARED_NUMBER, new BigDecimal("2000.00"), null));

        entityManager.flush();
    }

    @Test
    void sameNumberTwiceInOneOrganization_isRejectedAsADuplicate() {
        createAs(first, SHARED_NUMBER, new BigDecimal("1000.00"), null);

        assertThatExceptionOfType(DuplicateResourceException.class).isThrownBy(() ->
                createAs(first, SHARED_NUMBER, new BigDecimal("2000.00"), null));
    }

    // --- Opening paid amount (issue #603) ---------------------------------

    @Test
    void openingAmountPaidAboveTheRecordedAmount_isRejected() {
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                createAs(first, "PAY-2026-000002", new BigDecimal("1000.00"), new BigDecimal("5000.00")));
    }

    @Test
    void openingAmountPaidWithinTheRecordedAmount_isAccepted() {
        PayableDto created = createAs(first, "PAY-2026-000003",
                new BigDecimal("1000.00"), new BigDecimal("400.00"));

        assertThat(created.getAmountPaid()).isEqualByComparingTo("400.00");
        assertThat(created.getAmountDue()).isEqualByComparingTo("600.00");
    }

    @Test
    void openingAmountPaidEqualToTheRecordedAmount_isAcceptedAndSettled() {
        PayableDto created = createAs(first, "PAY-2026-000004",
                new BigDecimal("1000.00"), new BigDecimal("1000.00"));

        assertThat(created.getAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void absentOpeningAmountPaid_defaultsToZero() {
        PayableDto created = createAs(first, "PAY-2026-000005", new BigDecimal("1000.00"), null);

        assertThat(created.getAmountPaid()).isEqualByComparingTo("0.00");
    }

    @Test
    void negativeOpeningAmountPaid_isRejected() {
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                createAs(first, "PAY-2026-000006", new BigDecimal("1000.00"), new BigDecimal("-1.00")));
    }

    @Test
    void nonPositiveAmountRecorded_isRejected() {
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                createAs(first, "PAY-2026-000007", BigDecimal.ZERO, null));
    }
}
