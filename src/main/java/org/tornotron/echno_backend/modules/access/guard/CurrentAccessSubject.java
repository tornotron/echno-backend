package org.tornotron.echno_backend.modules.access.guard;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Who the current request is asking for: the signed-in user's membership in the organization the
 * request is scoped to.
 *
 * <p>Empty when there is no organization in scope, no signed-in user, or the user is not an
 * employee of that organization; every check then refuses. Resolved once per request and kept on
 * the request, since a single request can run several checks.
 */
@Component
public class CurrentAccessSubject {

    static final String ATTRIBUTE = CurrentAccessSubject.class.getName() + ".";

    private final UserContextService userContext;
    private final EmployeeRepository employees;

    public CurrentAccessSubject(UserContextService userContext, EmployeeRepository employees) {
        this.userContext = userContext;
        this.employees = employees;
    }

    public Optional<AccessSubject> resolve() {
        Long organizationId = TenantContext.getCurrentOrgId();
        if (organizationId == null) {
            return Optional.empty();
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        String key = ATTRIBUTE + organizationId;
        if (request != null && request.getAttribute(key, RequestAttributes.SCOPE_REQUEST) instanceof AccessSubject cached) {
            return Optional.of(cached);
        }
        Long userId = userContext.getCurrentUserId();
        if (userId == null) {
            return Optional.empty();
        }
        Optional<AccessSubject> subject = employees.findByUserIdAndOrganizationId(userId, organizationId)
                .map(employee -> new AccessSubject(organizationId, employee.getId(), userId));
        if (request != null) {
            subject.ifPresent(found -> request.setAttribute(key, found, RequestAttributes.SCOPE_REQUEST));
        }
        return subject;
    }
}
