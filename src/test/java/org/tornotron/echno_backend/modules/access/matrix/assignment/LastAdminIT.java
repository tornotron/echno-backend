package org.tornotron.echno_backend.modules.access.matrix.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;

/** Design note 2.6, rule 5: there is always an administrator, counted inside the transaction. */
class LastAdminIT extends RoleAssignmentITSupport {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Employee theOnlyAdministrator() {
        Employee admin = employee("only-admin");
        employeeService.assignOrgRoleAsSystem(admin.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        return admin;
    }

    private Employee aSecondAdministrator() {
        Employee second = employee("second-admin");
        employeeService.assignOrgRoleAsSystem(second.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        return second;
    }

    @Test
    void theLastAdministratorCannotHaveTheRoleTaken() {
        Employee admin = theOnlyAdministrator();
        signInAsSomeoneWithNoEmployeeHolding("system-admin");

        assertThatThrownBy(() -> employeeService.removeOrgRole(admin.getId(), OrgRole.SYSTEM_ADMIN))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("last active System Admin");
        assertThat(legacyRowsOf(admin)).containsExactly("SYSTEM_ADMIN");
    }

    @Test
    void anotherAdministratorCanHaveTheRoleTaken() {
        Employee admin = theOnlyAdministrator();
        Employee second = aSecondAdministrator();
        signInAs(admin, "system-admin");

        employeeService.removeOrgRole(second.getId(), OrgRole.SYSTEM_ADMIN);

        assertThat(legacyRowsOf(second)).isEmpty();
        assertThat(assignmentsOf(second)).isEmpty();
    }

    @Test
    void theLastAdministratorCannotBeStoppedByAStatusChange() {
        Employee admin = theOnlyAdministrator();
        signInAs(admin, "system-admin");

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(Map.of("status", "resigned"), admin.getId()))
                .isInstanceOf(InvalidRequestException.class);
        flush();
        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE id = ?", String.class, admin.getId()))
                .isEqualTo("active");
    }

    @Test
    void aStatusThatKeepsAccessIsNotRefused() {
        Employee admin = theOnlyAdministrator();
        signInAs(admin, "system-admin");

        employeeService.partialUpdateAnEmployee(Map.of("status", "onLeave"), admin.getId());
        flush();

        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE id = ?", String.class, admin.getId()))
                .isEqualTo("onLeave");
    }

    @Test
    void theLastAdministratorCannotBeDeleted() {
        Employee admin = theOnlyAdministrator();
        signInAs(admin, "system-admin");

        assertThatThrownBy(() -> employeeService.deleteAnEmployee(admin.getId()))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void anInactiveAdministratorDoesNotCountAndIsNotProtected() {
        Employee stopped = theOnlyAdministrator();
        jdbc.update("UPDATE employee SET status = 'resigned' WHERE id = ?", stopped.getId());
        em.clear();
        Employee active = aSecondAdministrator();
        signInAs(active, "system-admin");

        employeeService.removeOrgRole(stopped.getId(), OrgRole.SYSTEM_ADMIN);

        assertThat(legacyRowsOf(stopped)).isEmpty();
        signInAsSomeoneWithNoEmployeeHolding("system-admin");
        assertThatThrownBy(() -> employeeService.removeOrgRole(active.getId(), OrgRole.SYSTEM_ADMIN))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoConcurrentRemovalsCannotLeaveNobody() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long[] ids = new Long[5];
        transaction.executeWithoutResult(status -> {
            AccessFixtures rows = new AccessFixtures(em);
            Organization org = rows.organization("Concurrent admins " + System.nanoTime());
            Employee first = rows.employee(org, "concurrent-first-" + System.nanoTime());
            Employee second = rows.employee(org, "concurrent-second-" + System.nanoTime());
            em.flush();
            ids[0] = org.getId();
            ids[1] = first.getId();
            ids[2] = second.getId();
            ids[3] = first.getUser().getId();
            ids[4] = second.getUser().getId();
            TenantContext.setCurrentOrgId(org.getId());
        });
        String firstSubject = jdbc.queryForObject("SELECT keycloak_id FROM users_table WHERE id = ?", String.class, ids[3]);
        String secondSubject = jdbc.queryForObject("SELECT keycloak_id FROM users_table WHERE id = ?", String.class, ids[4]);
        transaction.executeWithoutResult(status -> {
            employeeService.assignOrgRoleAsSystem(ids[1], OrgRole.SYSTEM_ADMIN);
            employeeService.assignOrgRoleAsSystem(ids[2], OrgRole.SYSTEM_ADMIN);
        });
        TenantContext.clear();

        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> succeeded = Collections.synchronizedList(new ArrayList<>());
        Thread oneWay = removal(transaction, start, succeeded, ids[0], firstSubject, ids[2]);
        Thread otherWay = removal(transaction, start, succeeded, ids[0], secondSubject, ids[1]);
        start.countDown();
        oneWay.join(60_000);
        otherWay.join(60_000);

        try {
            assertThat(succeeded).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM access_employee_role a JOIN access_role r ON r.id = a.role_id
                    WHERE r.organization_id = ? AND r.slug = 'system-admin'
                    """, Integer.class, ids[0])).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM employee WHERE id IN (?, ?)", ids[1], ids[2]);
            jdbc.update("DELETE FROM users_table WHERE id IN (?, ?)", ids[3], ids[4]);
            jdbc.update("DELETE FROM access_change_log WHERE organization_id = ?", ids[0]);
            jdbc.update("DELETE FROM organization WHERE id = ?", ids[0]);
        }
    }

    private Thread removal(TransactionTemplate transaction, CountDownLatch start, List<Boolean> succeeded,
                           Long org, String callerSubject, Long target) {
        Thread thread = new Thread(() -> {
            TenantContext.setCurrentOrgId(org);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    callerSubject, null, List.of(new SimpleGrantedAuthority("ORG_" + org + "_ROLE_system-admin"))));
            try {
                start.await();
                transaction.executeWithoutResult(status -> employeeService.removeOrgRole(target, OrgRole.SYSTEM_ADMIN));
                succeeded.add(true);
            } catch (Exception refusedOrAborted) {
                succeeded.add(false);
            } finally {
                TenantContext.clear();
                SecurityContextHolder.clearContext();
            }
        });
        thread.start();
        return thread;
    }
}
