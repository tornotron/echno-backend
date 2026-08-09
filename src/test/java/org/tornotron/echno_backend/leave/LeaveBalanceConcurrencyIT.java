package org.tornotron.echno_backend.leave;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test for the leave-balance mutation lock against a real CockroachDB.
 * Several threads adjust the same balance at once; without the pessimistic write
 * lock that {@code adjustBalance} now takes, they read the same accrued value and
 * write over each other, erasing adjustments. With the lock they serialize.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LeaveBalanceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private LeaveBalanceRepository balanceRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAdjustments_doNotLoseUpdates() throws Exception {
        int year = 2026;
        long[] ids = new TransactionTemplate(txManager).execute(status -> {
            Organization org = new Organization();
            org.setOrganizationName("Leave Org");
            org.setOrganizationAddress("addr");
            org.setOrganizationEmail("leave@example.test");
            org.setOrganizationPhone("0000000000");
            entityManager.persist(org);

            User user = new User();
            user.setKeycloakId("kc-leave");
            user.setName("leave-user");
            entityManager.persist(user);

            Employee employee = new Employee();
            employee.setOrganization(org);
            employee.setUser(user);
            employee.setEmployeeName("leave-user");
            employee.setGender("U");
            employee.setPhoneNumber("0000000000");
            employee.setEmailAddress("leave@emp.test");
            employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
            entityManager.persist(employee);

            LeavePolicy policy = new LeavePolicy();
            policy.setOrganization(org);
            policy.setLeaveTypeCode("AL");
            policy.setLeaveTypeName("Annual Leave");
            policy.setAnnualQuota(20.0);
            policy.setCreatedAt(LocalDateTime.now());
            policy.setUpdatedAt(LocalDateTime.now());
            entityManager.persist(policy);

            LeaveBalance balance = new LeaveBalance();
            balance.setOrganization(org);
            balance.setEmployee(employee);
            balance.setLeavePolicy(policy);
            balance.setYear(year);
            balance.setAccrued(0.0);
            balance.setCreatedAt(LocalDateTime.now());
            entityManager.persist(balance);

            entityManager.flush();
            return new long[] {employee.getId(), policy.getId()};
        });

        long employeeId = ids[0];
        long policyId = ids[1];
        int threads = 4;
        double increment = 5.0;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    LeaveBalance balance = balanceRepository
                            .lockByEmployeeIdAndLeavePolicyIdAndYear(employeeId, policyId, year)
                            .orElseThrow();
                    balance.setAccrued(balance.getAccrued() + increment);
                    balanceRepository.save(balance);
                });
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        LeaveBalance finalBalance = balanceRepository
                .findByEmployeeIdAndLeavePolicyIdAndYear(employeeId, policyId, year)
                .orElseThrow();
        assertThat(finalBalance.getAccrued()).isEqualTo(threads * increment);
    }
}
