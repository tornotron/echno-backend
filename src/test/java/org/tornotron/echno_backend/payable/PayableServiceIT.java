package org.tornotron.echno_backend.payable;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.mapper.PayableMapperImpl;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link PayableService#recordPayment} against a real CockroachDB.
 * The concurrency IT proves the lock stops lost updates; this pins the money rules the
 * lock guards: payments must be positive, must not overpay the recorded amount, and
 * accumulate exactly across instalments up to the recorded total.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PayableService.class, PayableMapperImpl.class, EmployeeMapperImpl.class,
        AttachmentMapperImpl.class, TenantEntityHelper.class})
class PayableServiceIT extends AbstractIntegrationTest {

    @Autowired
    private PayableService payableService;

    @Autowired
    private PayableRepository payableRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // The attachment mapper in the payable mapper chain autowires this; nothing
    // in these payment tests touches file storage, so a mock satisfies the graph.
    @MockitoBean
    private FileStorageService fileStorageService;

    private Long orgId;
    private Long payableId;

    @BeforeEach
    void seed() {
        Organization org = new Organization();
        org.setOrganizationName("Payable Service Org");
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("payable-service@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);

        Project project = new Project();
        project.setProjectName("Project 1");
        project.setOrganization(org);
        entityManager.persist(project);

        Payable payable = new Payable();
        payable.setPayableNumber("PAY-SVC-0001");
        payable.setContractorName("ACME Contractors");
        payable.setProject(project);
        payable.setOrganization(org);
        payable.setAmountRecorded(new BigDecimal("1000.00"));
        payable.setAmountPaid(BigDecimal.ZERO);
        entityManager.persist(payable);

        entityManager.flush();
        orgId = org.getId();
        payableId = payable.getId();
        TenantContext.setCurrentOrgId(orgId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // --- Validation -------------------------------------------------------

    @Test
    void zeroPayment_isRejected() {
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                payableService.recordPayment(payableId, BigDecimal.ZERO));
    }

    @Test
    void negativePayment_isRejected() {
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                payableService.recordPayment(payableId, new BigDecimal("-50.00")));
    }

    @Test
    void nullPayment_isRejected() {
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                payableService.recordPayment(payableId, null));
    }

    @Test
    void overpayment_isRejected() {
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                payableService.recordPayment(payableId, new BigDecimal("1200.00")));
    }

    @Test
    void paymentOnUnknownPayable_isRejected() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() ->
                payableService.recordPayment(999_999L, new BigDecimal("10.00")));
    }

    // --- Accumulation -----------------------------------------------------

    @Test
    void partialPayments_accumulateAndLeaveBalance() {
        payableService.recordPayment(payableId, new BigDecimal("400.00"));
        payableService.recordPayment(payableId, new BigDecimal("300.00"));

        Payable reloaded = reload();
        assertThat(reloaded.getAmountPaid()).isEqualByComparingTo("700.00");
        assertThat(reloaded.getAmountDue()).isEqualByComparingTo("300.00");
    }

    @Test
    void paymentToExactRecordedAmount_isAllowedAndSettles() {
        payableService.recordPayment(payableId, new BigDecimal("1000.00"));

        Payable reloaded = reload();
        assertThat(reloaded.getAmountPaid()).isEqualByComparingTo("1000.00");
        assertThat(reloaded.getAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void overpaymentAfterPartialPayment_isRejected() {
        payableService.recordPayment(payableId, new BigDecimal("600.00"));

        // 600 already paid; a further 500 would exceed the recorded 1000.
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                payableService.recordPayment(payableId, new BigDecimal("500.00")));

        // The rejected payment must not have moved the balance.
        assertThat(reload().getAmountPaid()).isEqualByComparingTo("600.00");
    }

    private Payable reload() {
        entityManager.flush();
        entityManager.clear();
        return payableRepository.findByIdAndOrganization_Id(payableId, orgId).orElseThrow();
    }
}
