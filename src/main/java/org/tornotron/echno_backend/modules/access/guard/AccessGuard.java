package org.tornotron.echno_backend.modules.access.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;

/**
 * The {@code @access} bean {@code @PreAuthorize} expressions ask, in place of the role-name
 * checks:
 * <pre>
 *   &#64;PreAuthorize("&#64;access.can('wbs', 'edit')")
 *   &#64;PreAuthorize("&#64;access.isMember()")
 * </pre>
 * Both answer for the current request's subject and refuse when there is none. A malformed area
 * or action also answers false, so a typo in a guard refuses the caller and never becomes a 500;
 * {@code AccessGuardLiteralTest} fails the build on one before it gets that far.
 *
 * <p>Checks on one record, such as whether a client may open this bill, are made in the service
 * with {@link AccessService#check(org.tornotron.echno_backend.modules.access.api.AccessSubject,
 * AccessPermission, org.tornotron.echno_backend.modules.access.api.RecordFacts)}, where the record
 * is at hand; a guard only decides whether the caller may use the area at all.
 */
@Component("access")
@Slf4j
public class AccessGuard {

    private final CurrentAccessSubject current;
    private final AccessService access;

    public AccessGuard(CurrentAccessSubject current, AccessService access) {
        this.current = current;
        this.access = access;
    }

    /** Whether the caller is an active member of the request's organization. */
    public boolean isMember() {
        return current.resolve().map(access::isActiveMember).orElse(false);
    }

    /** Whether the caller may do {@code action} somewhere in {@code area}. */
    public boolean can(String area, String action) {
        AccessPermission permission;
        try {
            permission = new AccessPermission(area, action);
        } catch (IllegalArgumentException e) {
            log.error("Refusing a guard with a malformed permission: {}", e.getMessage());
            return false;
        }
        return current.resolve().map(subject -> access.check(subject, permission)).orElse(false);
    }
}
