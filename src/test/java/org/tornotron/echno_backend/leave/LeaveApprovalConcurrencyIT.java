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
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test for the leave-approval lock against a real CockroachDB.
 * Several threads try to approve the same pending request at once; each mirrors
 * the service guard (approve only while still PENDING_APPROVAL). Without the
 * pessimistic write lock the approve/reject paths now take, every thread reads
 * the stale PENDING status and "approves", double-processing. With the lock they
 * serialize, so exactly one approval wins.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LeaveApprovalConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private LeaveRequestRepository requestRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentApprovals_onlyOneWins() throws Exception {
        long requestId = new TransactionTemplate(txManager).execute(status -> {
            Organization org = new Organization();
            org.setOrganizationName("Approval Org");
            org.setOrganizationAddress("addr");
            org.setOrganizationEmail("approval@example.test");
            org.setOrganizationPhone("0000000000");
            entityManager.persist(org);

            User user = new User();
            user.setKeycloakId("kc-approval");
            user.setName("approval-user");
            entityManager.persist(user);

            Employee employee = new Employee();
            employee.setOrganization(org);
            employee.setUser(user);
            employee.setEmployeeName("approval-user");
            employee.setGender("U");
            employee.setPhoneNumber("0000000000");
            employee.setEmailAddress("approval@emp.test");
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

            LeaveRequest request = new LeaveRequest();
            request.setRequestNumber("LR-0001");
            request.setEmployee(employee);
            request.setOrganization(org);
            request.setLeavePolicy(policy);
            request.setStartDate(LocalDate.of(2026, 9, 1));
            request.setEndDate(LocalDate.of(2026, 9, 2));
            request.setTotalDays(2.0);
            request.setReason("Personal");
            request.setStatus(LeaveStatus.PENDING_APPROVAL);
            request.setCreatedAt(LocalDateTime.now());
            request.setUpdatedAt(LocalDateTime.now());
            entityManager.persist(request);

            entityManager.flush();
            return request.getId();
        });

        long orgId = new TransactionTemplate(txManager).execute(status ->
                requestRepository.findById(requestId).orElseThrow().getOrganization().getId());

        int threads = 4;
        AtomicInteger approvals = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    LeaveRequest request = requestRepository
                            .lockByIdAndOrganizationId(requestId, orgId)
                            .orElseThrow();
                    // Mirror the service guard: only approve while still pending.
                    if (request.getStatus() == LeaveStatus.PENDING_APPROVAL) {
                        request.setStatus(LeaveStatus.APPROVED);
                        requestRepository.save(request);
                        approvals.incrementAndGet();
                    }
                });
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(approvals.get()).isEqualTo(1);
    }
}
