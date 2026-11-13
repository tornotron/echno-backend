package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapperImpl;
import org.tornotron.echno_backend.attendance.mapper.AttendanceRegularizationMapperImpl;
import org.tornotron.echno_backend.attendance.mapper.ClockEventMapperImpl;
import org.tornotron.echno_backend.attendance.mapper.MovementRecordMapperImpl;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.attendance.service.AttendanceCalculationService;
import org.tornotron.echno_backend.attendance.service.AttendanceGeofenceService;
import org.tornotron.echno_backend.attendance.service.AttendanceSettingsService;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.multitenancy.HibernateFilterConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.provider.AttendanceProvider;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * End-to-end tenant isolation check for {@link AttendanceProvider} against the real database,
 * real {@link AttendanceService} pagination, and the production {@code orgFilter} AOP/Hibernate
 * wiring.
 *
 * <p>Two organizations store attendance under the exact same {@code projectId} and {@code date},
 * with organization A spanning two pages (205 rows) across every {@link AttendanceStatus} and
 * organization B holding 3 rows. Any missing tenant filter mixes both counts together.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class, SimpleMeterRegistry.class, TransactionalWorkRunner.class,
        TenantScopedJobRunner.class, AttendanceMapperImpl.class, ShiftTimingMapperImpl.class,
        ClockEventMapperImpl.class, AttachmentMapperImpl.class, AttendanceRegularizationMapperImpl.class,
        MovementRecordMapperImpl.class, AttendanceService.class, AttendanceProvider.class,
        AttendanceProviderIT.TestConfig.class})
// Services and TenantScopedJobRunner open and scope their own transactions per call.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AttendanceProviderIT extends AbstractIntegrationTest {

    private static final long SHARED_PROJECT_ID = 9400L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);
    private static final Question QUESTION = new Question("How many were on site today?");

    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private AssistantProvider attendanceProvider;

    @Autowired
    private TenantScopedJobRunner tenantScopedJobRunner;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean(name = "attendanceSecurity")
    private AttendanceSecurityService attendanceSecurity;

    @MockitoBean
    private AttendanceSettingsService settingsService;

    @MockitoBean
    private AttendanceCalculationService calculationService;

    @MockitoBean
    private ClockEventSequenceValidator sequenceValidator;

    @MockitoBean
    private AttachmentService attachmentService;

    @MockitoBean
    private FileStorageService fileStorageService;

    @MockitoBean
    private UserContextService userContextService;

    @MockitoBean
    private PayloadValidator payloadValidator;

    @MockitoBean
    private AttendanceGeofenceService geofenceService;

    private Long orgAId;
    private Long orgBId;
    private long nextEmployeeId = 50_000L;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        TenantContext.declareUnscoped("AttendanceProviderIT seed");
        try {
            inCommittedTx(() -> {
                Organization orgA = persistOrganization("Attendance Provider Org A");
                Organization orgB = persistOrganization("Attendance Provider Org B");
                orgAId = orgA.getId();
                orgBId = orgB.getId();

                // 205 rows in org A (2 pages at PAGE_SIZE=200): 200 on-site, 5 off-site/unresolved.
                for (int i = 0; i < 196; i++) {
                    persistAttendance(orgA, AttendanceStatus.PRESENT, 480, 0);
                }
                persistAttendance(orgA, AttendanceStatus.LATE, 420, 0);
                persistAttendance(orgA, AttendanceStatus.HALF_DAY, 240, 0);
                persistAttendance(orgA, AttendanceStatus.EARLY_CHECKOUT, 360, 0);
                persistAttendance(orgA, AttendanceStatus.OVERTIME, 540, 60);
                persistAttendance(orgA, AttendanceStatus.ABSENT, 0, 0);
                persistAttendance(orgA, AttendanceStatus.LEAVE, 0, 0);
                persistAttendance(orgA, AttendanceStatus.WEEKLY_OFF, 0, 0);
                persistAttendance(orgA, AttendanceStatus.HOLIDAY, 0, 0);
                persistAttendance(orgA, AttendanceStatus.PENDING_REGULARIZATION, 0, 0);

                // 3 rows in org B on the exact same projectId and date.
                for (int i = 0; i < 3; i++) {
                    persistAttendance(orgB, AttendanceStatus.PRESENT, 300, 15);
                }
                entityManager.flush();
            });
        } finally {
            TenantContext.clear();
        }
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("caller", null, List.of()));
        when(attendanceSecurity.canManageRecords()).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        if (orgAId == null) {
            return;
        }
        TenantContext.setBypass(true);
        try {
            inCommittedTx(() -> {
                entityManager.createNativeQuery("DELETE FROM attendance WHERE organization_id IN (:a,:b)")
                        .setParameter("a", orgAId).setParameter("b", orgBId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM organization WHERE id IN (:a,:b)")
                        .setParameter("a", orgAId).setParameter("b", orgBId).executeUpdate();
            });
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void onlyThePinnedOrganizationsAttendanceIsReadAcrossPages() {
        Scope scope = new Scope(SHARED_PROJECT_ID, DAY, DAY, null);

        TenantContext.setCurrentOrgId(orgAId);
        ProviderResult resultA = attendanceProvider.retrieve(QUESTION, scope);

        List<EvidenceUnit> unitsA = ((ProviderResult.Evidence) resultA).units();
        assertThat(unitsA).hasSize(1);
        EvidenceUnit summaryA = unitsA.get(0);
        assertThat(summaryA.values())
                .containsEntry("recordCount", 205)
                .containsEntry("onSiteCount", 200)
                .containsEntry("totalWorkMinutes", 196L * 480 + 420 + 240 + 360 + 540)
                .containsEntry("overtimeMinutes", 60L);
        @SuppressWarnings("unchecked")
        Map<String, Integer> statusCountsA = (Map<String, Integer>) summaryA.values().get("statusCounts");
        assertThat(statusCountsA).containsEntry("PRESENT", 196)
                .containsEntry("ABSENT", 1)
                .containsEntry("HOLIDAY", 1)
                .hasSize(AttendanceStatus.values().length);

        TenantContext.setCurrentOrgId(orgBId);
        ProviderResult resultB = attendanceProvider.retrieve(QUESTION, scope);

        List<EvidenceUnit> unitsB = ((ProviderResult.Evidence) resultB).units();
        assertThat(unitsB).hasSize(1);
        assertThat(unitsB.get(0).values())
                .containsEntry("recordCount", 3)
                .containsEntry("onSiteCount", 3)
                .containsEntry("totalWorkMinutes", 900L)
                .containsEntry("overtimeMinutes", 45L);
    }

    @Test
    void tenantScopedJobRunnerInTransactionAlsoKeepsOrganizationsIsolated() {
        Scope scope = new Scope(SHARED_PROJECT_ID, DAY, DAY, null);

        ProviderResult resultA = tenantScopedJobRunner.callForTenantInTransaction(
                orgAId, () -> attendanceProvider.retrieve(QUESTION, scope));
        ProviderResult resultB = tenantScopedJobRunner.callForTenantInTransaction(
                orgBId, () -> attendanceProvider.retrieve(QUESTION, scope));

        assertThat(((ProviderResult.Evidence) resultA).units().get(0).values())
                .containsEntry("recordCount", 205)
                .containsEntry("onSiteCount", 200);
        assertThat(((ProviderResult.Evidence) resultB).units().get(0).values())
                .containsEntry("recordCount", 3)
                .containsEntry("onSiteCount", 3);
    }

    @Test
    void unscopedCallDoesNotLeakRowsAcrossOrganizations() {
        TenantContext.clear();

        ProviderResult result = attendanceProvider.retrieve(
                QUESTION, new Scope(SHARED_PROJECT_ID, DAY, DAY, null));

        assertThat(result).isInstanceOf(ProviderResult.Unavailable.class);
    }

    // ---------------------------------------------------------- helpers

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    private void persistAttendance(Organization org, AttendanceStatus status, int workMinutes, int overtimeMinutes) {
        long employeeId = nextEmployeeId++;
        Attendance attendance = Attendance.builder()
                .employeeId(employeeId)
                .employeeName(String.format("Employee %05d", employeeId))
                .attendanceDate(DAY)
                .projectId(SHARED_PROJECT_ID)
                .projectName("Shared Site")
                .status(status)
                .totalWorkMinutes(workMinutes)
                .overtimeMinutes(overtimeMinutes)
                .approvalStatus(ApprovalStatus.PENDING)
                .requiresGeofenceApproval(false)
                .organization(org)
                .build();
        entityManager.persist(attendance);
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        org.setIsActive(true);
        entityManager.persist(org);
        return org;
    }
}

