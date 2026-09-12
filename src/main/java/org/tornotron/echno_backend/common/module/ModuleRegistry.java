package org.tornotron.echno_backend.common.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Every installed module, collected from the {@link EchnoModule} beans on the context, and the
 * two runtime questions asked of them.
 *
 * <p>{@link #installed()} is fixed at boot: it is what was compiled in. Whether a module is on for
 * an organization is decided per call by {@link #isEnabledForOrg(String, Long)}, which requires
 * all of: the module is installed, its kill switch {@code echno.modules.<id>.enabled} is not
 * {@code false}, the organization is entitled to it, and every module it depends on is itself
 * enabled for that organization.
 *
 * <p>Entitlement means: for a module with an {@code entitlementFeatureKey}, whatever the
 * {@link ModuleEntitlementResolver} says; for a module without one, its
 * {@code enabledByDefault} flag.
 *
 * <p>Construction validates the manifests and fails the boot on a duplicate id, a dependency on
 * a module that is not installed, or a dependency cycle. A module that would silently not work
 * is worse than one that stops the application from starting.
 */
@Component
public class ModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);

    static final String KILL_SWITCH_PREFIX = "echno.modules.";
    static final String KILL_SWITCH_SUFFIX = ".enabled";

    private final SortedMap<String, ModuleManifest> manifests;
    private final Map<String, List<NavDescriptor>> navByModule;
    private final Map<String, List<String>> permissionsByModule;
    private final ModuleEntitlementResolver entitlementResolver;
    private final Environment environment;

    @Autowired
    public ModuleRegistry(ObjectProvider<EchnoModule> modules,
                          ModuleEntitlementResolver entitlementResolver,
                          Environment environment) {
        this(modules.orderedStream().toList(), entitlementResolver, environment);
    }

    public ModuleRegistry(List<EchnoModule> modules,
                          ModuleEntitlementResolver entitlementResolver,
                          Environment environment) {
        this.entitlementResolver = entitlementResolver;
        this.environment = environment;

        SortedMap<String, ModuleManifest> byId = new TreeMap<>();
        Map<String, List<NavDescriptor>> nav = new LinkedHashMap<>();
        Map<String, List<String>> permissions = new LinkedHashMap<>();
        for (EchnoModule module : modules) {
            ModuleManifest manifest = module.manifest();
            if (manifest == null) {
                throw new IllegalStateException(
                        "Module bean " + module.getClass().getName() + " returned a null manifest");
            }
            ModuleManifest previous = byId.put(manifest.id(), manifest);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two modules declare the id '" + manifest.id() + "'");
            }
            nav.put(manifest.id(), collectNavigation(module, manifest));
            permissions.put(manifest.id(), collectPermissions(module, manifest));
        }
        validateDependencies(byId);

        this.manifests = Collections.unmodifiableSortedMap(byId);
        this.navByModule = Collections.unmodifiableMap(nav);
        this.permissionsByModule = Collections.unmodifiableMap(permissions);

        if (manifests.isEmpty()) {
            log.info("No modules installed");
        } else {
            log.info("Modules installed: {}", manifests.keySet());
        }
    }

    /** Every module compiled into this build, in id order. */
    public Collection<ModuleManifest> installed() {
        return manifests.values();
    }

    public boolean isInstalled(String moduleId) {
        return manifests.containsKey(moduleId);
    }

    /** The manifest of an installed module, or throws if no module has that id. */
    public ModuleManifest manifest(String moduleId) {
        ModuleManifest manifest = manifests.get(moduleId);
        if (manifest == null) {
            throw new IllegalArgumentException("No module with id '" + moduleId + "' is installed");
        }
        return manifest;
    }

    /**
     * The operator kill switch: {@code echno.modules.<id>.enabled}, {@code true} unless the
     * property is set to {@code false}. Installed-level, independent of any organization.
     */
    public boolean isSwitchedOn(String moduleId) {
        return isInstalled(moduleId) && environment.getProperty(
                KILL_SWITCH_PREFIX + moduleId + KILL_SWITCH_SUFFIX, Boolean.class, Boolean.TRUE);
    }

    /**
     * Whether the organization is entitled to the module, ignoring the kill switch and the
     * module's dependencies. A paywalled module asks the resolver; a free one answers with its
     * {@code enabledByDefault} flag.
     */
    public boolean isEntitled(String moduleId, Long organizationId) {
        if (!isInstalled(moduleId)) {
            return false;
        }
        ModuleManifest manifest = manifests.get(moduleId);
        if (!manifest.isPaywalled()) {
            return manifest.enabledByDefault();
        }
        return entitlementResolver.isEntitled(organizationId, manifest.entitlementFeatureKey());
    }

    /**
     * Installed, switched on, entitled, and every dependency enabled for the same organization.
     */
    public boolean isEnabledForOrg(String moduleId, Long organizationId) {
        if (!isSwitchedOn(moduleId) || !isEntitled(moduleId, organizationId)) {
            return false;
        }
        for (String dependency : manifests.get(moduleId).dependsOn()) {
            if (!isEnabledForOrg(dependency, organizationId)) {
                return false;
            }
        }
        return true;
    }

    /** The manifests enabled for the organization, in id order. */
    public Collection<ModuleManifest> enabledForOrg(Long organizationId) {
        List<ModuleManifest> enabled = new ArrayList<>();
        for (ModuleManifest manifest : manifests.values()) {
            if (isEnabledForOrg(manifest.id(), organizationId)) {
                enabled.add(manifest);
            }
        }
        return enabled;
    }

    /** The module's nav descriptors: the manifest's, then those registered through the hook. */
    public List<NavDescriptor> navDescriptors(String moduleId) {
        return navByModule.getOrDefault(moduleId, List.of());
    }

    /** The module's permission keys: the manifest's, then those registered through the hook. */
    public List<String> permissions(String moduleId) {
        return permissionsByModule.getOrDefault(moduleId, List.of());
    }

    private static List<NavDescriptor> collectNavigation(EchnoModule module, ModuleManifest manifest) {
        NavRegistry registry = new NavRegistry();
        module.registerNavigation(registry);
        List<NavDescriptor> merged = new ArrayList<>(manifest.navDescriptors());
        merged.addAll(registry.descriptors());
        return List.copyOf(merged);
    }

    private static List<String> collectPermissions(EchnoModule module, ModuleManifest manifest) {
        PermissionRegistry registry = new PermissionRegistry();
        module.registerPermissions(registry);
        Set<String> merged = new LinkedHashSet<>(manifest.permissions());
        merged.addAll(registry.keys());
        return List.copyOf(merged);
    }

    private static void validateDependencies(Map<String, ModuleManifest> byId) {
        List<String> missing = new ArrayList<>();
        for (ModuleManifest manifest : byId.values()) {
            for (String dependency : manifest.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    missing.add(manifest.id() + " -> " + dependency);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Modules depend on modules that are not installed: " + missing);
        }
        for (String id : byId.keySet()) {
            List<String> cycle = findCycle(id, byId);
            if (cycle != null) {
                throw new IllegalStateException("Module dependencies form a cycle: " + cycle);
            }
        }
    }

    private static List<String> findCycle(String start, Map<String, ModuleManifest> byId) {
        Deque<String> path = new ArrayDeque<>();
        return walk(start, byId, path, new HashSet<>());
    }

    private static List<String> walk(String current, Map<String, ModuleManifest> byId,
                                     Deque<String> path, Set<String> onPath) {
        if (onPath.contains(current)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(current);
            return cycle;
        }
        path.addLast(current);
        onPath.add(current);
        for (String dependency : byId.get(current).dependsOn()) {
            List<String> cycle = walk(dependency, byId, path, onPath);
            if (cycle != null) {
                return cycle;
            }
        }
        path.removeLast();
        onPath.remove(current);
        return null;
    }
}
