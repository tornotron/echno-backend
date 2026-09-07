package org.tornotron.echno_backend.material.lowstock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.NotificationDraft;
import org.tornotron.echno_backend.leave.NotificationService;
import org.tornotron.echno_backend.leave.enums.NotificationType;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the reorder sweep decides, once it has a project's answer in front of it.
 *
 * <p>Everything here calls {@code runPass()} directly, so none of it says anything about whether
 * the job is ever triggered. That is deliberately a separate question and it is asked in
 * {@link LowStockSweepScheduleTest}, which reads the declaration rather than the logic. A test
 * that calls a scheduled method and passes proves the method works and proves nothing whatsoever
 * about the schedule.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LowStockSweepTest {

    private static final Long ORG = 7L;
    private static final Long PROJECT = 12L;
    private static final Long MATERIAL = 55L;

    @Mock private LowStockRepository lowStockRepository;
    @Mock private MaterialReorderAlertRepository alertRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private NotificationService notificationService;

    private LowStockSweepProperties properties;
    private LowStockSweep sweep;

    /** Every organization id the pass established a tenant for, in order. */
    private final List<Long> scopedTo = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new LowStockSweepProperties();

        // The real runner, not a stub. It is what refuses a null organization id, and stubbing it
        // out would remove the only thing standing between a scheduled job and every tenant's
        // rows. The list records what it was asked to scope to.
        TenantScopedJobRunner runner = new TenantScopedJobRunner() {
            @Override
            public <T> T callForTenant(Long orgId, java.util.function.Supplier<T> work) {
                scopedTo.add(orgId);
                return super.callForTenant(orgId, work);
            }
        };

        sweep = new LowStockSweep(lowStockRepository, alertRepository, employeeRepository,
                organizationRepository, projectRepository, materialRepository, notificationService,
                runner, properties);

        when(lowStockRepository.findProjectsHoldingStock(any(Pageable.class)))
                .thenReturn(List.of(new StockedProject(ORG, PROJECT)));
        when(alertRepository.findByOrganization_IdAndProject_Id(ORG, PROJECT))
                .thenReturn(List.of());
        when(alertRepository.countByNotifiedAtGreaterThanEqual(any())).thenReturn(0L);
        when(lowStockRepository.findLowStockForProject(eq(ORG), eq(PROJECT), any(Pageable.class)))
                .thenReturn(emptyPage());
        when(lowStockRepository.findProjectStockForMaterials(anyLong(), anyLong(), any()))
                .thenReturn(List.of());
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project("Marina Tower")));
        when(projectRepository.getReferenceById(PROJECT)).thenReturn(project("Marina Tower"));
        when(organizationRepository.getReferenceById(ORG)).thenReturn(new Organization());
        when(materialRepository.getReferenceById(MATERIAL)).thenReturn(new Material());
        when(alertRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    @DisplayName("Who is told")
    class Recipients {

        @Test
        @DisplayName("the storekeepers on the project, who are the people who can act on it")
        void tellsTheProjectsStorekeepers() {
            Employee keeper = employee(101L);
            lowMaterial(30.0, 4.0);
            when(employeeRepository.findByProjectAndOrgRole(ORG, PROJECT, OrgRole.STORE_KEEPER))
                    .thenReturn(List.of(keeper));

            sweep.runPass();

            assertThat(deliveredTo()).containsExactly(keeper);
        }

        @Test
        @DisplayName("the project managers only when the project has no storekeeper")
        void fallsBackToProjectManagers() {
            Employee manager = employee(202L);
            lowMaterial(30.0, 4.0);
            when(employeeRepository.findByProjectAndOrgRole(ORG, PROJECT, OrgRole.STORE_KEEPER))
                    .thenReturn(List.of());
            when(employeeRepository.findByProjectAndOrgRole(ORG, PROJECT, OrgRole.PROJECT_MANAGER))
                    .thenReturn(List.of(manager));

            sweep.runPass();

            assertThat(deliveredTo()).containsExactly(manager);
        }

        @Test
        @DisplayName("nobody, rather than an administrator, when the project has neither")
        void neverFallsBackToAdministrators() {
            lowMaterial(30.0, 4.0);
            when(employeeRepository.findByProjectAndOrgRole(eq(ORG), eq(PROJECT), any()))
                    .thenReturn(List.of());

            sweep.runPass();

            verifyNoInteractions(notificationService);
            // An organization administrator is a recipient the sweep must never reach for. It is
            // the person least able to act on a site they do not run and the one most likely to
            // mute the whole category, which loses every later crossing with it.
            verify(employeeRepository, never())
                    .findByOrganizationIdAndOrgRole(anyLong(), eq(OrgRole.SYSTEM_ADMIN));
            verify(employeeRepository, never())
                    .findByOrganizationIdAndOrgRole(anyLong(), eq(OrgRole.ORG_MANAGER));
        }

        @Test
        @DisplayName("a crossing with nobody to tell is not latched, so it survives to be told later")
        void doesNotSpendTheCrossingOnNobody() {
            lowMaterial(30.0, 4.0);
            when(employeeRepository.findByProjectAndOrgRole(eq(ORG), eq(PROJECT), any()))
                    .thenReturn(List.of());

            sweep.runPass();

            verify(alertRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("one notification row per recipient, because a role has no row of its own")
        void fansOutOneRowPerRecipient() {
            Employee first = employee(101L);
            Employee second = employee(102L);
            lowMaterial(30.0, 4.0);
            when(employeeRepository.findByProjectAndOrgRole(ORG, PROJECT, OrgRole.STORE_KEEPER))
                    .thenReturn(List.of(first, second));

            sweep.runPass();

            assertThat(deliveredTo()).containsExactly(first, second);
            assertThat(latched().getRecipientCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Crossing, not being below")
    class Suppression {

        @Test
        @DisplayName("a material found low with no latch is reported and latched")
        void reportsTheFirstCrossing() {
            lowMaterial(30.0, 4.0);
            withStorekeeper();

            sweep.runPass();

            MaterialReorderAlert alert = latched();
            assertThat(alert.getNotifiedLevel()).isEqualTo(30.0);
            assertThat(alert.getNotifiedQuantity()).isEqualTo(4.0);
            assertThat(alert.getNotifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("a material still below its same level is not reported again")
        void staysQuietWhileTheMaterialStaysDown() {
            lowMaterial(30.0, 2.0);
            withStorekeeper();
            when(alertRepository.findByOrganization_IdAndProject_Id(ORG, PROJECT))
                    .thenReturn(List.of(latch(30.0, 4.0)));

            sweep.runPass();

            verifyNoInteractions(notificationService);
            verify(alertRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a material whose level has moved is a new event, not the same one continuing")
        void reportsAgainWhenTheLevelChanges() {
            // Reported at 30, and somebody has since raised the level to 100. The material is
            // below a line nobody has been told about, and without this it would stay latched
            // permanently at exactly the moment it became badly short.
            lowMaterial(100.0, 40.0);
            withStorekeeper();
            when(alertRepository.findByOrganization_IdAndProject_Id(ORG, PROJECT))
                    .thenReturn(List.of(latch(30.0, 4.0)));

            sweep.runPass();

            assertThat(deliveredTo()).hasSize(1);
            assertThat(latched().getNotifiedLevel()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("a hovering material is not re-reported the moment it ticks above its level")
        void doesNotRearmOnASingleUnit() {
            // Level 30, latched, and stock has crept back to 31. That is inside the rearm margin,
            // so the latch stands and tomorrow's dip below 30 says nothing new.
            withStorekeeper();
            when(alertRepository.findByOrganization_IdAndProject_Id(ORG, PROJECT))
                    .thenReturn(List.of(latch(30.0, 4.0)));
            when(lowStockRepository.findProjectStockForMaterials(eq(ORG), eq(PROJECT), any()))
                    .thenReturn(List.of(new ProjectMaterialStock(MATERIAL, 30.0, 31.0)));

            sweep.runPass();

            verify(alertRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("a material that recovers past the margin is rearmed")
        void rearmsOnRealRecovery() {
            withStorekeeper();
            when(alertRepository.findByOrganization_IdAndProject_Id(ORG, PROJECT))
                    .thenReturn(List.of(latch(30.0, 4.0)));
            when(lowStockRepository.findProjectStockForMaterials(eq(ORG), eq(PROJECT), any()))
                    .thenReturn(List.of(new ProjectMaterialStock(MATERIAL, 30.0, 40.0)));

            sweep.runPass();

            assertThat(deleted()).hasSize(1);
        }

        @Test
        @DisplayName("a level of zero rearms at anything on hand, with no special case for it")
        void rearmsAZeroLevelAtAnythingAtAll() {
            // The margin is a fraction of the level, and a fraction of zero is zero. Somebody who
            // set the level to zero asked to be told when the material is gone, so one unit back
            // on the shelf is a real recovery.
            withStorekeeper();
            when(alertRepository.findByOrganization_IdAndProject_Id(ORG, PROJECT))
                    .thenReturn(List.of(latch(0.0, 0.0)));
            when(lowStockRepository.findProjectStockForMaterials(eq(ORG), eq(PROJECT), any()))
                    .thenReturn(List.of(new ProjectMaterialStock(MATERIAL, 0.0, 1.0)));

            sweep.runPass();

            assertThat(deleted()).hasSize(1);
        }

        @Test
        @DisplayName("a latch whose level has been cleared is dropped, because no line is drawn any more")
        void dropsTheLatchWhenTheLevelIsUnset() {
            withStorekeeper();
            when(alertRepository.findByOrganization_IdAndProject_Id(ORG, PROJECT))
                    .thenReturn(List.of(latch(30.0, 4.0)));
            when(lowStockRepository.findProjectStockForMaterials(eq(ORG), eq(PROJECT), any()))
                    .thenReturn(List.of(new ProjectMaterialStock(MATERIAL, null, 0.0)));

            sweep.runPass();

            assertThat(deleted()).hasSize(1);
        }

        @Test
        @DisplayName("a latch for a material that holds nothing on the project stands")
        void keepsTheLatchWhenTheMaterialHasNothingThere() {
            // Every stock row gone reads as nothing on hand rather than as recovery, and
            // re-reporting a material that holds nothing would say nothing new.
            withStorekeeper();
            when(alertRepository.findByOrganization_IdAndProject_Id(ORG, PROJECT))
                    .thenReturn(List.of(latch(30.0, 4.0)));
            when(lowStockRepository.findProjectStockForMaterials(eq(ORG), eq(PROJECT), any()))
                    .thenReturn(List.of(new ProjectMaterialStock(MATERIAL, 30.0, 0.0)));

            sweep.runPass();

            verify(alertRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("losing the latch race to another replica is not a failure")
        void survivesALostLatchRace() {
            lowMaterial(30.0, 4.0);
            withStorekeeper();
            when(alertRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("uq_material_reorder_alert_scope"));

            sweep.runPass();
            // The pass carries on rather than ending the night for every other project.
        }
    }

    @Nested
    @DisplayName("What it must not do")
    class Boundaries {

        @Test
        @DisplayName("every read and write of a tenant's rows happens under that tenant")
        void establishesTheTenantBeforeReadingAnything() {
            lowMaterial(30.0, 4.0);
            withStorekeeper();

            sweep.runPass();

            assertThat(scopedTo).containsExactly(ORG);
            // And the context is put back, so an organization id cannot leak onto the pooled
            // scheduler thread and silently scope whatever runs there next.
            assertThat(TenantContext.getCurrentOrgId()).isNull();
        }

        @Test
        @DisplayName("it notifies and raises no procurement document")
        void raisesNoPaperwork() {
            lowMaterial(30.0, 4.0);
            withStorekeeper();

            sweep.runPass();

            NotificationDraft draft = draft();
            assertThat(draft.type()).isEqualTo(NotificationType.MATERIAL_LOW_STOCK);
            assertThat(draft.entityType()).isEqualTo("MATERIAL");
            assertThat(draft.entityId()).isEqualTo(MATERIAL);
            // The sweep holds no procurement repository at all, so there is nothing for it to
            // raise. The constructor is the assertion; this records why.
        }

        @Test
        @DisplayName("a project that fails does not cost the rest of the estate its night")
        void carriesOnAfterAFailingProject() {
            when(lowStockRepository.findProjectsHoldingStock(any(Pageable.class)))
                    .thenReturn(List.of(new StockedProject(ORG, 999L), new StockedProject(ORG, PROJECT)));
            when(lowStockRepository.findLowStockForProject(eq(ORG), eq(999L), any(Pageable.class)))
                    .thenThrow(new IllegalStateException("bad project"));
            lowMaterial(30.0, 4.0);
            withStorekeeper();

            sweep.runPass();

            assertThat(deliveredTo()).hasSize(1);
        }

        @Test
        @DisplayName("the per-run cap counts what every replica raised, not just this one")
        void sharesTheCapAcrossReplicas() {
            properties.setMaxNotificationsPerRun(5);
            when(alertRepository.countByNotifiedAtGreaterThanEqual(any())).thenReturn(5L);
            lowMaterial(30.0, 4.0);
            withStorekeeper();

            sweep.runPass();

            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("the message names the shortfall in the material's own unit")
        void saysWhatIsShort() {
            lowMaterial(30.0, 4.0);
            withStorekeeper();

            sweep.runPass();

            assertThat(draft().title()).isEqualTo("Low stock: TNT Steel");
            assertThat(draft().message())
                    .contains("TNT Steel")
                    .contains("4 kg")
                    .contains("Marina Tower")
                    .contains("30");
        }
    }

    // ---- fixtures -------------------------------------------------------------------------

    private void lowMaterial(Double level, Double onHand) {
        when(lowStockRepository.findLowStockForProject(eq(ORG), eq(PROJECT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new LowStockRow(
                        MATERIAL, "TNT-01", "TNT Steel", "kg", 100.0, level, onHand))));
    }

    private void withStorekeeper() {
        when(employeeRepository.findByProjectAndOrgRole(ORG, PROJECT, OrgRole.STORE_KEEPER))
                .thenReturn(List.of(employee(101L)));
    }

    private MaterialReorderAlert latch(Double level, Double quantity) {
        MaterialReorderAlert alert = new MaterialReorderAlert();
        alert.setId(1L);
        Material material = new Material();
        material.setId(MATERIAL);
        alert.setMaterial(material);
        alert.setNotifiedLevel(level);
        alert.setNotifiedQuantity(quantity);
        alert.setRecipientCount(1);
        alert.setNotifiedAt(LocalDateTime.now().minusDays(3));
        return alert;
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setOrganization(new Organization());
        return employee;
    }

    private Project project(String name) {
        Project project = new Project();
        project.setId(PROJECT);
        project.setProjectName(name);
        return project;
    }

    private Page<LowStockRow> emptyPage() {
        return new PageImpl<>(List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Employee> deliveredTo() {
        ArgumentCaptor<Collection<Employee>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(notificationService).deliverToAll(captor.capture(), any());
        return new ArrayList<>(captor.getValue());
    }

    private NotificationDraft draft() {
        ArgumentCaptor<NotificationDraft> captor = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(notificationService).deliverToAll(any(), captor.capture());
        return captor.getValue();
    }

    private MaterialReorderAlert latched() {
        ArgumentCaptor<MaterialReorderAlert> captor =
                ArgumentCaptor.forClass(MaterialReorderAlert.class);
        verify(alertRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<MaterialReorderAlert> deleted() {
        ArgumentCaptor<Iterable<MaterialReorderAlert>> captor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(alertRepository).deleteAll(captor.capture());
        List<MaterialReorderAlert> all = new ArrayList<>();
        captor.getValue().forEach(all::add);
        return all;
    }
}
