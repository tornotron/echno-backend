package org.tornotron.echno_backend.modules.workprogress.billing.payment.authorizer;

import org.tornotron.echno_backend.modules.access.api.CallerAccess;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.PaymentAuthorizerChangeDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.ProjectPaymentAuthorizerDto;
import org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport;
import org.tornotron.echno_backend.modules.workprogress.time.WorkProgressClock;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

/**
 * Who may give the second signature on a project's bill payments, and naming that person.
 *
 * <p>Each project names one employee as its payment authorizer. Whoever may manage the project (a
 * system admin or a project manager, who hold {@code progress-bills:edit}) names
 * or changes them, so an organization cannot lock itself out; holding either role does not by itself
 * let anyone authorize a payment. The authority is checked here, inside the transaction that changes
 * the payment, so a change of authorizer and a signature cannot cross.
 *
 * <p>This is the only route to the authority on this branch, because the organization roles have no
 * finance role. Under the access matrix a role granted {@code progress-bills:authorize-payment} holds it
 * as well, on the projects its scope covers; that check belongs beside {@link #holds}.
 */
@Component
public class PaymentAuthorizers {

    /** Statuses in which an employee no longer acts for the organization. */
    private static final Set<EmployeeStatus> GONE = EnumSet.of(EmployeeStatus.inactive, EmployeeStatus.terminated,
            EmployeeStatus.resigned, EmployeeStatus.suspended);

    private static final String MANAGERS = "A system admin or a project manager can name one on the project's billing page.";

    /**
     * Who may name or change a project's authorizer: the action the naming endpoints ask for. Before
     * the matrix the system admin and the project manager, whose starting grants hold it.
     */
    private static final String SETUP_AREA = "progress-bills";
    private static final String SETUP_ACTION = "edit";

    private static final int HISTORY = 20;

    private final ProjectPaymentAuthorizerRepository authorizers;
    private final ProjectPaymentAuthorizerChangeRepository changes;
    private final EmployeeRepository employees;
    private final ProjectRepository projects;
    private final BillingSupport support;
    private final CallerAccess access;
    private final Clock clock;

    public PaymentAuthorizers(ProjectPaymentAuthorizerRepository authorizers,
                              ProjectPaymentAuthorizerChangeRepository changes, EmployeeRepository employees,
                              ProjectRepository projects, BillingSupport support,
                              CallerAccess access, @WorkProgressClock Clock clock) {
        this.authorizers = authorizers;
        this.changes = changes;
        this.employees = employees;
        this.projects = projects;
        this.support = support;
        this.access = access;
        this.clock = clock;
    }

    /**
     * The person a project names, as a payment needs it.
     *
     * @param userId the authorizer's sign-in, which is what a payment's signatures record
     * @param active false once the employee has left, which leaves the project with no authorizer
     */
    public record Holder(Long employeeId, String name, Long userId, boolean active) {

        /** Whether this is the given user, acting for the organization. */
        public boolean is(Long user) {
            return active && user != null && user.equals(userId);
        }
    }

    // ---------------------------------------------------------------- for payments

    /** The project's authorizer, if it names one. Empty for a project of another organization. */
    @Transactional(readOnly = true)
    public Optional<Holder> holderOf(Long orgId, Long projectId) {
        return authorizers.findForProject(orgId, projectId)
                .flatMap(row -> employees.findByIdAndOrganizationIdWithUser(row.getEmployeeId(), orgId))
                .map(PaymentAuthorizers::holder);
    }

    /** Whether the user holds the payment authority on the project. */
    @Transactional(readOnly = true)
    public boolean holds(Long orgId, Long projectId, Long userId) {
        return holderOf(orgId, projectId).map(holder -> holder.is(userId)).orElse(false);
    }

    /**
     * Refuses a user who does not hold the payment authority on the project, saying who does.
     *
     * @throws AccessDeniedException if the project has no active authorizer or it is someone else
     */
    @Transactional(readOnly = true)
    public Holder require(Long orgId, Long projectId, Long userId) {
        Optional<Holder> named = holderOf(orgId, projectId);
        if (named.isEmpty()) {
            throw new AccessDeniedException("This project has no payment authorizer, so its payments wait. " + MANAGERS);
        }
        Holder holder = named.get();
        if (!holder.active()) {
            throw new AccessDeniedException("This project's payment authorizer, " + holder.name()
                    + ", is no longer active, so its payments wait. " + MANAGERS);
        }
        if (!holder.is(userId)) {
            throw new AccessDeniedException("Only this project's payment authorizer, " + holder.name()
                    + ", can authorize, reject or record its payments.");
        }
        return holder;
    }

    // ---------------------------------------------------------------- naming the authorizer

    @Transactional(readOnly = true)
    public ProjectPaymentAuthorizerDto view(Long projectId, Long reader) {
        Long orgId = TenantContext.getCurrentOrgId();
        Project project = requireProject(projectId, orgId);
        return toDto(project, orgId, reader);
    }

    /**
     * Names the project's authorizer, replacing any before. Naming the one already named changes
     * nothing and records nothing.
     *
     * @throws InvalidRequestException if the employee has left the organization
     * @throws ResourceNotFoundException if the project or the employee is not in this organization
     */
    @Transactional
    public ProjectPaymentAuthorizerDto set(Long projectId, Long employeeId, Long actor) {
        Long orgId = TenantContext.getCurrentOrgId();
        Project project = requireProject(projectId, orgId);
        Employee employee = employees.findByIdAndOrganizationIdWithUser(employeeId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));
        if (GONE.contains(employee.getStatus())) {
            throw new InvalidRequestException(employee.getEmployeeName() + " is " + employee.getStatus()
                    + " and cannot authorize payments; name an active employee");
        }
        Optional<ProjectPaymentAuthorizer> current = authorizers.lockForProject(orgId, projectId);
        Long previous = current.map(ProjectPaymentAuthorizer::getEmployeeId).orElse(null);
        if (Objects.equals(previous, employeeId)) {
            return toDto(project, orgId, actor);
        }
        ProjectPaymentAuthorizer row = current.orElseGet(() -> {
            ProjectPaymentAuthorizer created = new ProjectPaymentAuthorizer();
            created.setOrganization(project.getOrganization());
            created.setProjectId(projectId);
            return created;
        });
        row.setEmployeeId(employeeId);
        row.setSetBy(actor);
        row.setSetAt(now());
        authorizers.save(row);
        recordChange(project, orgId, previous, employeeId, employee.getEmployeeName(), actor);
        return toDto(project, orgId, actor);
    }

    /** Leaves the project with no authorizer. Its payments then wait until one is named. */
    @Transactional
    public ProjectPaymentAuthorizerDto clear(Long projectId, Long actor) {
        Long orgId = TenantContext.getCurrentOrgId();
        Project project = requireProject(projectId, orgId);
        authorizers.lockForProject(orgId, projectId).ifPresent(row -> {
            authorizers.delete(row);
            recordChange(project, orgId, row.getEmployeeId(), null, null, actor);
        });
        return toDto(project, orgId, actor);
    }

    // ---------------------------------------------------------------- helpers

    private void recordChange(Project project, Long orgId, Long previous, Long next, String nextName, Long actor) {
        ProjectPaymentAuthorizerChange change = new ProjectPaymentAuthorizerChange();
        change.setOrganization(project.getOrganization());
        change.setProjectId(project.getId());
        change.setPreviousEmployeeId(previous);
        change.setPreviousEmployeeName(previous == null ? null : employeeName(previous, orgId));
        change.setEmployeeId(next);
        change.setEmployeeName(nextName);
        change.setChangedBy(actor);
        change.setChangedByName(support.nameOf(actor, orgId));
        change.setChangedAt(now());
        changes.save(change);
    }

    private ProjectPaymentAuthorizerDto toDto(Project project, Long orgId, Long reader) {
        Optional<ProjectPaymentAuthorizer> row = authorizers.findForProject(orgId, project.getId());
        Optional<Holder> holder = row.flatMap(r -> employees.findByIdAndOrganizationIdWithUser(r.getEmployeeId(), orgId))
                .map(PaymentAuthorizers::holder);
        List<PaymentAuthorizerChangeDto> history = changes.findForProject(orgId, project.getId(), PageRequest.of(0, HISTORY))
                .stream()
                .map(c -> new PaymentAuthorizerChangeDto(c.getId(), c.getPreviousEmployeeId(), c.getPreviousEmployeeName(),
                        c.getEmployeeId(), c.getEmployeeName(), c.getChangedByName(), c.getChangedAt()))
                .toList();
        return new ProjectPaymentAuthorizerDto(project.getId(), project.getProjectName(),
                holder.map(Holder::employeeId).orElse(null), holder.map(Holder::name).orElse(null),
                holder.map(Holder::active).orElse(false),
                row.map(r -> support.nameOf(r.getSetBy(), orgId)).orElse(null),
                row.map(ProjectPaymentAuthorizer::getSetAt).orElse(null),
                holder.map(h -> h.is(reader)).orElse(false),
                access.can(SETUP_AREA, SETUP_ACTION), history);
    }

    private String employeeName(Long employeeId, Long orgId) {
        return employees.findByIdAndOrganizationId(employeeId, orgId).map(Employee::getEmployeeName).orElse(null);
    }

    private Project requireProject(Long projectId, Long orgId) {
        return projects.findByIdAndOrganization_Id(projectId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    private static Holder holder(Employee employee) {
        return new Holder(employee.getId(), employee.getEmployeeName(), employee.getUser().getId(),
                !GONE.contains(employee.getStatus()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
