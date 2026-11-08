package org.tornotron.echno_backend.expense;

import java.util.Set;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;

/**
 * An engine for the expense and receipt service tests: read, create and edit on one project's records,
 * and nothing else, unless a test widens it to everything or takes it all away.
 */
public final class OneProjectExpenseAccess implements AccessService {

    private static final Set<String> HELD = Set.of(AccessPermission.READ, AccessPermission.CREATE, AccessPermission.EDIT);

    private final Long project;
    public boolean everything;
    public boolean nothing;

    public OneProjectExpenseAccess(Long project) {
        this.project = project;
    }

    @Override
    public boolean isActiveMember(AccessSubject subject) {
        return true;
    }

    @Override
    public boolean check(AccessSubject subject, AccessPermission permission) {
        return !nothing && HELD.contains(permission.action());
    }

    @Override
    public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
        return filter(subject, permission).admits(record);
    }

    @Override
    public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
        if (nothing || !HELD.contains(permission.action()) || !ExpenseAccess.AREA.equals(permission.area())) {
            return AccessFilter.none(subject.organizationId());
        }
        if (everything) {
            return AccessFilter.all(subject.organizationId());
        }
        return new AccessFilter(subject.organizationId(), false, Set.of(project), null, null);
    }

    @Override
    public AccessGrants grantsFor(AccessSubject subject) {
        return AccessGrants.none();
    }
}
