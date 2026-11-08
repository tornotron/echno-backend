package org.tornotron.echno_backend.modules.access.matrix.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.guard.EnforcedPermissions;

/**
 * The catalog as the screen needs it: the areas in {@link AccessCatalog}, the permissions module
 * manifests declare, and for each action whether a guard enforces it. An action nothing enforces
 * is shown as not yet controlled here, with no choice to make, until its area is converted. An
 * area that filters its records by scope says so, and only such an area offers a scope other than
 * all records.
 */
@Component
public class AccessCatalogReader {

    /** Where a module with no entry in {@link AccessCatalog} is listed. */
    static final String MODULES_SECTION = "Modules";

    /** One action of an area, and whether some guard checks it. */
    public record ActionEntry(String action, boolean enforced) {
    }

    /**
     * One area with its actions.
     *
     * @param scopeAware whether the area filters its records by scope, so a grant on it may cover
     *                   fewer than all records
     */
    public record AreaEntry(String id, String label, String section, List<ActionEntry> actions, boolean scopeAware) {
    }

    private final ModuleRegistry modules;
    private final EnforcedPermissions enforced;
    private final ScopeAwareAreas scopeAware;

    public AccessCatalogReader(ModuleRegistry modules, EnforcedPermissions enforced, ScopeAwareAreas scopeAware) {
        this.modules = modules;
        this.enforced = enforced;
        this.scopeAware = scopeAware;
    }

    /** Every area, in the catalog's order, then the modules it does not list, by id. */
    public List<AreaEntry> areas() {
        Map<String, Draft> drafts = new LinkedHashMap<>();
        AccessCatalog.areas().forEach(area ->
                drafts.put(area.id(), new Draft(area.label(), area.section(), new LinkedHashSet<>(area.actions()))));
        for (ModuleManifest manifest : modules.installed()) {
            Draft draft = drafts.computeIfAbsent(manifest.id(), id -> new Draft(
                    manifest.name(), MODULES_SECTION, new LinkedHashSet<>(AccessCatalog.COMMON_ACTIONS)));
            modules.permissions(manifest.id()).forEach(key -> {
                AccessPermission permission = AccessPermission.of(key);
                if (permission.area().equals(manifest.id())) {
                    draft.actions().add(permission.action());
                }
            });
        }
        List<AreaEntry> areas = new ArrayList<>();
        drafts.forEach((id, draft) -> areas.add(new AreaEntry(id, draft.label(), draft.section(),
                draft.actions().stream()
                        .map(action -> new ActionEntry(action, enforced.isEnforced(new AccessPermission(id, action))))
                        .toList(),
                scopeAware.isScopeAware(id))));
        return areas;
    }

    /** Whether the catalog or a module manifest declares this permission. */
    public boolean declares(AccessPermission permission) {
        return areas().stream().anyMatch(area -> area.id().equals(permission.area())
                && area.actions().stream().anyMatch(entry -> entry.action().equals(permission.action())));
    }

    private record Draft(String label, String section, Set<String> actions) {
    }
}
