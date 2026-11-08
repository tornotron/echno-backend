package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessServiceContract;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessPartyLink;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The shared contract run against the whole engine on CockroachDB: the tables, the fact queries,
 * the cache and the decisions. Grants go through a role assignment, on a project or across the
 * organization, the way the application will write them. Each set-up step drops the cache, as a
 * writer would after committing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Customer carries Spring Data's AuditingEntityListener, so this slice imports the auditing
// configuration for a handler of its own; without it the listener is configured by whichever earlier
// context last enabled auditing, and a persist fails once the context cache has closed that one.
@Import({AccessFactsQueries.class, DatabaseAccessFactsSource.class, JpaAuditingConfig.class})
class DatabaseAccessServiceContractIT extends AbstractIntegrationTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private DatabaseAccessFactsSource database;

    @Nested
    class Engine extends AccessServiceContract {

        private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
        private final Map<Long, Project> projects = new HashMap<>();
        private final Map<Long, Employee> employees = new HashMap<>();

        private AccessFixtures fixtures;
        private Organization organization;
        private World world;
        private int roles;

        private void buildTheWorld() {
            fixtures = new AccessFixtures(em.getEntityManager());
            organization = fixtures.organization("Contract Org");
            Organization other = fixtures.organization("Contract Other Org");
            Project a = fixtures.project(organization, "Contract A");
            Project b = fixtures.project(organization, "Contract B");
            projects.put(a.getId(), a);
            projects.put(b.getId(), b);
            // A status never set is how older rows look, and it holds nothing, which is what the
            // contract needs of a subject who has not been made a member.
            Employee alice = fixtures.employee(organization, "contract-alice", null);
            Employee bob = fixtures.employee(organization, "contract-bob", null);
            employees.put(alice.getId(), alice);
            employees.put(bob.getId(), bob);
            em.flush();
            UUID customer = fixtures.customer(organization, "CT-1").getId();
            UUID otherCustomer = fixtures.customer(organization, "CT-2").getId();
            long vendor = fixtures.vendor(organization, "Contract Vendor").getId();
            long otherVendor = fixtures.vendor(organization, "Contract Other Vendor").getId();
            world = new World(organization.getId(), other.getId(), a.getId(), b.getId(), customer, otherCustomer,
                    vendor, otherVendor,
                    new AccessSubject(organization.getId(), alice.getId(), alice.getUser().getId()),
                    new AccessSubject(organization.getId(), bob.getId(), bob.getUser().getId()));
        }

        @Override
        protected World world() {
            if (world == null) {
                buildTheWorld();
            }
            return world;
        }

        @Override
        protected AccessService service() {
            return new MatrixAccessService(new CachedAccessFactsSource(database, cacheManager));
        }

        private void changed() {
            em.flush();
            cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        }

        @Override
        protected void member(AccessSubject subject) {
            employees.get(subject.employeeId()).setStatus(EmployeeStatus.active);
            changed();
        }

        @Override
        protected void deactivate(AccessSubject subject) {
            employees.get(subject.employeeId()).setStatus(EmployeeStatus.terminated);
            changed();
        }

        private void assignRole(AccessSubject subject, AccessPermission permission, AccessScope scope, Long projectId) {
            AccessRole role = fixtures.newRole(organization, "contract-role-" + (++roles));
            role.grant(permission, scope);
            em.persist(role);
            fixtures.assign(organization, employees.get(subject.employeeId()), role, projectId);
            changed();
        }

        @Override
        protected void grant(AccessSubject subject, AccessPermission permission, AccessScope scope) {
            assignRole(subject, permission, scope, null);
        }

        @Override
        protected void grantOnProject(AccessSubject subject, AccessPermission permission, AccessScope scope, long projectId) {
            assignRole(subject, permission, scope, projectId);
        }

        @Override
        protected void assignToProject(AccessSubject subject, long projectId) {
            projects.get(projectId).getEmployees().add(employees.get(subject.employeeId()));
            changed();
        }

        @Override
        protected void linkCustomer(AccessSubject subject, UUID customerId) {
            em.persist(AccessPartyLink.toCustomer(organization, subject.employeeId(), customerId));
            changed();
        }

        @Override
        protected void linkVendor(AccessSubject subject, long vendorId) {
            em.persist(AccessPartyLink.toVendor(organization, subject.employeeId(), vendorId));
            changed();
        }
    }
}
