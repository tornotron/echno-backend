package org.tornotron.echno_backend.finance.construction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionPaymentMapperImpl;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionPaymentRepository;
import org.tornotron.echno_backend.finance.construction.service.ConstructionPaymentService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

/**
 * End-to-end exercise of the construction payment CRUD path against a real
 * CockroachDB: create stores the voucher and generates a CPMT number, get and list
 * return it, update replaces its fields and sets the status directly, and the org
 * filter keeps one tenant's payments invisible to another.
 *
 * <p>The verification stamp is exercised here as well as in the unit tests, because it is the
 * half that depends on the schema: the raiser id the self-approval comparison reads is a column
 * added by changelog 080, and a migration that had not run would leave the rule with nothing to
 * compare while every mocked test still passed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConstructionPaymentService.class, ConstructionPaymentMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class, JpaAuditingConfig.class,
        SelfApprovalPolicy.class,
        org.tornotron.echno_backend.user.UserNameDirectory.class})
class ConstructionPaymentServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ConstructionPaymentService service;

    @Autowired
    private ConstructionPaymentRepository paymentRepo;

    @MockitoBean
    private UserContextService userContextService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private static final long RAISER = 7L;
    private static final long VERIFIER = 8L;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;

    // The tenant seed (orgs + project) must be committed, not held in the test's
    // rolled-back transaction: EntryNumberGenerator.next() runs in REQUIRES_NEW, so
    // the document_sequence insert commits in a separate transaction that only sees
    // committed rows. The payment itself stays in the rolled-back test transaction.
    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Org A");
            Organization orgB = persistOrganization("Org B");

            Project project = new Project();
            project.setProjectName("Tower A");
            project.setOrganization(orgA);
            entityManager.persist(project);

            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
        });

        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void cleanup() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
        if (orgAId == null && orgBId == null) {
            return;
        }
        // Committed seed rows survive the test rollback, so remove them by hand in
        // FK-safe order (also clears the committed document_sequence rows).
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM construction_payments WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    @Test
    void create_get_list_update_persistsVoucherAndScopesByTenant() {
        when(userContextService.getCurrentUserId()).thenReturn(RAISER);
        CreateConstructionPaymentRequest createReq = new CreateConstructionPaymentRequest(
                ConstructionPaymentType.INVOICE,
                ConstructionPaymentMethod.BANK_TRANSFER,
                ConstructionPayeeType.VENDOR,
                projectId,
                null, null,
                42L,
                null, null, null,
                "Acme Supplies", "Consumer No: 123456",
                bd("15000.50"),
                "INR",
                LocalDate.of(2026, 8, 1),
                "TXN-0001", "REF-0001", "State Bank", "0001112223", "SBIN0000001",
                "First running payment", "Approved by PM"
        );

        // create - status forced to PENDING, number generated, amount stored
        ConstructionPaymentDto created = service.create(createReq);
        assertThat(created.status()).isEqualTo(ConstructionPaymentVoucherStatus.PENDING);
        assertThat(created.paymentNumber()).startsWith("CPMT-");
        assertThat(created.amount()).isEqualByComparingTo(bd("15000.50"));
        assertThat(created.currency()).isEqualTo("INR");
        assertThat(created.type()).isEqualTo(ConstructionPaymentType.INVOICE);
        assertThat(created.method()).isEqualTo(ConstructionPaymentMethod.BANK_TRANSFER);
        assertThat(created.payeeType()).isEqualTo(ConstructionPayeeType.VENDOR);
        assertThat(created.vendorId()).isEqualTo(42L);

        UUID id = created.id();

        // get
        ConstructionPaymentDto fetched = service.findById(id);
        assertThat(fetched.id()).isEqualTo(id);
        assertThat(fetched.amount()).isEqualByComparingTo(bd("15000.50"));
        assertThat(fetched.payeeName()).isEqualTo("Acme Supplies");

        entityManager.flush();
        entityManager.clear();

        Pageable pageable = PageRequest.of(0, 10);

        // tenant scoping - invisible to another organization
        enableOrgFilter(orgBId);
        assertThat(service.findAll(null, null, null, null, null, pageable).getTotalElements()).isZero();
        assertThat(paymentRepo.findByIdScoped(id)).isEmpty();

        // visible and listable to the owning organization, including the filters
        disableOrgFilter();
        enableOrgFilter(orgAId);
        assertThat(service.findAll(null, null, null, null, null, pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.findAll(projectId, 42L, ConstructionPaymentVoucherStatus.PENDING,
                ConstructionPaymentType.INVOICE, ConstructionPayeeType.VENDOR, pageable)
                .getTotalElements()).isEqualTo(1);
        assertThat(paymentRepo.findByIdScoped(id)).isPresent();
        disableOrgFilter();

        // update - status set directly, fields replaced
        UpdateConstructionPaymentRequest updateReq = new UpdateConstructionPaymentRequest(
                ConstructionPaymentType.INVOICE,
                ConstructionPaymentVoucherStatus.COMPLETED,
                ConstructionPaymentMethod.UPI,
                ConstructionPayeeType.VENDOR,
                projectId,
                null, null,
                42L,
                null, null, null,
                "Acme Supplies", "Consumer No: 123456",
                bd("16000"),
                "INR",
                LocalDate.of(2026, 8, 2),
                "TXN-0002", "REF-0002", "State Bank", "0001112223", "SBIN0000001",
                "Revised running payment", "Settled"
        );

        ConstructionPaymentDto updated = service.update(id, updateReq);
        assertThat(updated.status()).isEqualTo(ConstructionPaymentVoucherStatus.COMPLETED);
        assertThat(updated.method()).isEqualTo(ConstructionPaymentMethod.UPI);
        assertThat(updated.amount()).isEqualByComparingTo(bd("16000"));
        assertThat(updated.paymentDate()).isEqualTo(LocalDate.of(2026, 8, 2));
    }

    @Test
    void verify_stampsTheSessionAgainstThePersistedRaiser_andRefusesASecondVerification() {
        when(userContextService.getCurrentUserId()).thenReturn(RAISER);
        ConstructionPaymentDto created = service.create(minimalCreateRequest());
        assertThat(created.raisedBy()).isEqualTo(RAISER);
        assertThat(created.verifiedBy()).isNull();
        assertThat(created.verifiedAt()).isNull();

        entityManager.flush();
        entityManager.clear();

        // The raiser is read back from the column, not from the object create left behind.
        when(userContextService.getCurrentUserId()).thenReturn(VERIFIER);
        ConstructionPaymentDto verified = service.verify(created.id());
        assertThat(verified.verifiedBy()).isEqualTo(VERIFIER);
        assertThat(verified.verifiedAt()).isNotNull();
        assertThat(verified.raisedBy()).isEqualTo(RAISER);

        entityManager.flush();
        entityManager.clear();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.verify(created.id()))
                .withMessageContaining("already been verified");
    }

    @Test
    void verify_refusesTheRaiserOfTheVoucher() {
        when(userContextService.getCurrentUserId()).thenReturn(RAISER);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);
        ConstructionPaymentDto created = service.create(minimalCreateRequest());

        entityManager.flush();
        entityManager.clear();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.verify(created.id()))
                .withMessageContaining("raised by the same person");

        assertThat(service.findById(created.id()).verifiedAt()).isNull();
    }

    @Test
    void aVerifiedVoucherIsFrozen_andCancellingIsWhatCorrectsIt() {
        when(userContextService.getCurrentUserId()).thenReturn(RAISER);
        ConstructionPaymentDto created = service.create(minimalCreateRequest());

        entityManager.flush();
        entityManager.clear();

        when(userContextService.getCurrentUserId()).thenReturn(VERIFIER);
        service.verify(created.id());

        entityManager.flush();
        entityManager.clear();

        // Read back from the row rather than from the object verify left behind.
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.update(created.id(), anEditRaisingTheAmount()))
                .withMessageContaining("cannot be edited");
        assertThat(service.findById(created.id()).amount()).isEqualByComparingTo(bd("1000"));

        entityManager.flush();
        entityManager.clear();

        // This half only passes because changelog 082 ran: cancellation_reason is a real column
        // here, not a field on a mock.
        ConstructionPaymentDto cancelled = service.cancel(created.id(), "Duplicate of the August run");

        entityManager.flush();
        entityManager.clear();

        ConstructionPaymentDto reread = service.findById(cancelled.id());
        assertThat(reread.status()).isEqualTo(ConstructionPaymentVoucherStatus.CANCELLED);
        assertThat(reread.cancellationReason()).isEqualTo("Duplicate of the August run");
        assertThat(reread.verifiedBy()).isEqualTo(VERIFIER);
    }

    /** An edit that moves the figure the verification was about. */
    private UpdateConstructionPaymentRequest anEditRaisingTheAmount() {
        return new UpdateConstructionPaymentRequest(
                ConstructionPaymentType.INVOICE,
                ConstructionPaymentVoucherStatus.COMPLETED,
                ConstructionPaymentMethod.BANK_TRANSFER,
                ConstructionPayeeType.VENDOR,
                projectId,
                null, null,
                42L,
                null, null, null,
                "Acme Supplies", null,
                bd("100000"),
                "INR",
                LocalDate.of(2026, 8, 1),
                null, null, null, null, null,
                "Running payment", null
        );
    }

    private CreateConstructionPaymentRequest minimalCreateRequest() {
        return new CreateConstructionPaymentRequest(
                ConstructionPaymentType.INVOICE,
                ConstructionPaymentMethod.BANK_TRANSFER,
                ConstructionPayeeType.VENDOR,
                projectId,
                null, null,
                42L,
                null, null, null,
                "Acme Supplies", null,
                bd("1000"),
                "INR",
                LocalDate.of(2026, 8, 1),
                null, null, null, null, null,
                "Running payment", null
        );
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
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

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
