package org.tornotron.echno_backend.modules.inspections.events;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionEventRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The one way an event enters the log.
 *
 * <p>Both entry points run with {@link Propagation#MANDATORY}: a service records inside the
 * same transaction that makes the change, so an event never exists without its change and a
 * change never commits without its event. Calling from outside a transaction is a programming
 * error and is refused rather than quietly given a transaction of its own.
 *
 * <p>The actor is resolved from the security context: the caller's employee record in the
 * current tenant when there is one, else the user id, else {@code SYSTEM}. The machine intake
 * and the compliance job name their actor explicitly through {@link #recordAs}.
 */
@Component
@RequiredArgsConstructor
public class InspectionEventRecorder {

    static final String REQUEST_ID_KEY = "requestId";

    private final InspectionEventRepository repository;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final EmployeeRepository employeeRepository;

    /** Records an event by the authenticated caller, or by the system when there is none. */
    @Transactional(propagation = Propagation.MANDATORY)
    public InspectionEvent record(InspectionEventSubject subject,
                                  String eventType,
                                  Map<String, Object> before,
                                  Map<String, Object> after,
                                  String note) {
        Actor actor = currentActor();
        return recordAs(subject, eventType, actor.type(), actor.id(), before, after, note);
    }

    /** Records an event by a named actor: a device, a model, or a scheduled job. */
    @Transactional(propagation = Propagation.MANDATORY)
    public InspectionEvent recordAs(InspectionEventSubject subject,
                                    String eventType,
                                    InspectionEventActorType actorType,
                                    String actorId,
                                    Map<String, Object> before,
                                    Map<String, Object> after,
                                    String note) {
        InspectionEvent event = InspectionEvent.of(
                tenantEntityHelper.resolveCurrentOrganization(),
                subject, eventType, actorType, actorId, LocalDateTime.now(),
                before, after, note, MDC.get(REQUEST_ID_KEY));
        return repository.save(event);
    }

    private record Actor(InspectionEventActorType type, String id) {}

    private Actor currentActor() {
        Long userId = userContextService.getCurrentUserId();
        if (userId == null) {
            return new Actor(InspectionEventActorType.SYSTEM, null);
        }
        return employeeRepository
                .findByUserIdAndOrganizationId(userId, TenantContext.getCurrentOrgId())
                .map(Employee::getId)
                .map(employeeId -> new Actor(InspectionEventActorType.USER, String.valueOf(employeeId)))
                .orElse(new Actor(InspectionEventActorType.USER, "user:" + userId));
    }
}
