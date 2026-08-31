package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against a public endpoint reading tenant-scoped data: nothing reachable without
 * authentication may end in a repository whose rows belong to an organization.
 *
 * <p>Since #527 the tenant layer fails closed. {@code TenantFilter} requires every request that
 * proceeds to declare a scope, and an undeclared load of a {@code TenantScopedEntity} is refused
 * at the load boundary. An unauthenticated request cannot supply an organization id, so the
 * filter declares it unscoped in good faith, with the reason "Unauthenticated request to
 * &lt;path&gt;". That declaration is correct for registration, which genuinely runs before a
 * tenant exists, and it is also exactly what switches the load boundary off for the rest of the
 * request.
 *
 * <p>So the public paths are the one place where the fail-closed guard does not apply, and they
 * are the one place a reader is most likely to assume it does. If such an endpoint reads a
 * tenant-scoped entity, the read runs across every tenant at once and nothing refuses it. This
 * rule is what notices.
 *
 * <p><b>What counts as public.</b> {@link EndpointAuthorizationTest} already requires every
 * request-mapped method in a {@code @RestController} to carry an explicit {@code @PreAuthorize},
 * with an empty grandfather set, so the annotation is the complete and authoritative marker of
 * an endpoint whose author intended it to be reachable unauthenticated. Any expression that
 * mentions {@code permitAll} or {@code isAnonymous} counts, not only a bare
 * {@code permitAll()}: a compound grant such as {@code "permitAll() or hasRole('ADMIN')"} is
 * reachable without authentication by its first term. The security config's
 * {@code permitAll} matcher list is the other half of the same decision, but it can only withhold
 * access, never grant it past a method-level {@code @PreAuthorize}, so reading the annotation
 * catches the hazard a matcher-list scan would and catches it one step earlier: at the moment the
 * handler is marked public, not at the moment the path is routed to it.
 *
 * <p>Two surfaces this rule deliberately does not cover, both documented rather than enforced.
 * {@code /actuator/**} is on the matcher list but is served entirely by Spring Boot's own
 * endpoints and is skipped by {@code TenantFilter} outright, so it declares nothing and an entity
 * load from there would be denied by the load boundary rather than let through: it is the one
 * public path that is still fail-closed. The Swagger paths reach springdoc, which reads Spring's
 * own request-mapping metadata and never the database. Neither has a handler of ours to walk out
 * from.
 *
 * <p>Nor does it cover the authenticated-but-unscoped paths that {@code TenantFilter} enumerates
 * ({@code /user/web} and the no-membership state). Those already read tenant-scoped rows
 * knowingly, scoped by the caller's own user id rather than by the tenant layer, so a rule over
 * them would be an allowlist of its own contents and would say nothing. {@code /billing/**} was
 * a third entry here and is no longer unscoped: the declaration left the tenant id null, which
 * refused every billing endpoint outright, and billing now runs inside tenant isolation like any
 * other authenticated surface. See #640.
 *
 * <h2>What the walk follows</h2>
 *
 * <p>Reachability is computed over method calls through this codebase's own classes, the same
 * walk {@link MapperDatabaseAccessTest} uses, for the same reason: neither hazard names its
 * repository directly, and a direct-call rule would pass the very code it exists to prevent.
 * Breadth first, so the reported path is the shortest one. Three hops are followed that a
 * plain call graph does not have, because this codebase routes real tenant reads through all
 * three and a gate that stopped at the first of them would report green over each of them.
 *
 * <p><b>Publishing an event.</b> {@code ApplicationEventPublisher.publishEvent} is a Spring
 * method, so a walk that stops at the framework drops it and never sees the listener behind
 * it. The GRN, site-transfer, consumption and chat services all move tenant work across that
 * hop. It is followed by matching the event this codebase constructs in the publishing method
 * against the parameter type of every {@code @EventListener} and
 * {@code @TransactionalEventListener}; where no event type can be identified at the publish
 * site, every listener is followed instead, which over-reports rather than misses.
 * {@code AFTER_COMMIT} is not an escape: that listener runs on the request thread, inside the
 * same unscoped declaration. An {@code @Async} listener is the one that is deliberately not
 * followed, and for the same reason {@code /actuator} is not covered: the thread hand-off
 * leaves the declaration behind, so the load boundary is back on and the read is refused
 * rather than let through.
 *
 * <p><b>Dropping below Spring Data.</b> A repository is not the only way to a row.
 * {@code EntityManager} query methods and {@code JdbcTemplate} are counted as reads in their
 * own right, which is what {@code ReportService}, {@code VendorSummaryService} and
 * {@code DocumentNumberAllocator} use today. Which table such a query touches is a string in
 * the source and not a fact in the bytecode, so unlike a repository these are reported whatever
 * they read. That is the deliberate direction: an unauthenticated request reaching hand-written
 * SQL deserves the argument, and the answer is to key the query on something the caller proved
 * rather than to widen this rule.
 *
 * <p><b>Calls typed as an interface.</b> A call through an interface resolves to the interface's
 * own declaration, which has no body, so the walk would stop one hop short of the implementation
 * that does the reading. Every implementation in the imported graph is followed as well.
 *
 * <p>One limit is left standing and is stated here rather than discovered later. Reachability
 * is over static calls, so anything dispatched by a value rather than by a type is invisible:
 * a lambda handed across a boundary, reflection, a Spring proxy resolved by name. Nothing on
 * a public path does that today, and the rule would have to become a data-flow analysis to
 * see it.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's own
 * cache, shared with every architecture test in this package. See {@link
 * UnboundedRepositoryReadTest} for why that matters in a 1 GB test JVM.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class PublicEndpointTenantExposureTest {

    private static final String PRODUCTION_PACKAGE = "org.tornotron.echno_backend.";

    /**
     * Cache of the tenant-scoped verdict per repository interface, so a wide call graph does not
     * re-walk the same generic hierarchy hundreds of times.
     */
    private static final Map<String, Boolean> TENANT_SCOPED_REPOSITORIES = new ConcurrentHashMap<>();

    /**
     * The rule. Every endpoint an unauthenticated caller can reach, walked out to the database.
     *
     * <p>Written as a plain assertion rather than as an {@code ArchRule} because the useful part
     * of the failure is the path from the handler to the repository, and a condition that reports
     * only the offending method leaves the reader to find the read themselves.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void noPublicEndpointReachesTenantScopedData(JavaClasses productionClasses) {
        List<JavaMethod> publicEndpoints = publicEndpoints(productionClasses);

        assertThat(publicEndpoints)
                .as("the rule has nothing to check, which means the way a public endpoint is "
                        + "marked has changed and this test now passes vacuously; find the new "
                        + "marker before deleting this assertion")
                .isNotEmpty();

        Reachability reachability = new Reachability(productionClasses);
        Map<String, List<String>> offenders = new LinkedHashMap<>();
        for (JavaMethod endpoint : publicEndpoints) {
            reachability.pathToTenantData(endpoint).ifPresent(path ->
                    offenders.put(name(endpoint.getOwner(), endpoint.getName()), path));
        }

        assertThat(offenders)
                .as("an unauthenticated request declares itself unscoped, which switches off the "
                        + "load boundary for the whole request, so a tenant-scoped read reached "
                        + "from a permitAll endpoint runs across every organization and nothing "
                        + "refuses it; either the endpoint does not belong on the permitAll list, "
                        + "or the read must be keyed on something the caller proved (an invite "
                        + "code, a signed token) and moved behind an authenticated path")
                .isEmpty();
    }

    /**
     * Every request-mapped controller method whose effective {@code @PreAuthorize} is
     * {@code permitAll()}. A method-level annotation wins over the class-level one, which is how
     * Spring resolves it.
     *
     * @param productionClasses The imported production classes.
     * @return The handlers an unauthenticated caller can reach.
     */
    static List<JavaMethod> publicEndpoints(JavaClasses productionClasses) {
        List<JavaMethod> endpoints = new ArrayList<>();
        for (JavaClass javaClass : productionClasses) {
            if (!javaClass.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.isMetaAnnotatedWith(RequestMapping.class)) {
                    continue;
                }
                if (isPermitAll(method)) {
                    endpoints.add(method);
                }
            }
        }
        return endpoints;
    }

    private static boolean isPermitAll(JavaMethod method) {
        if (method.isAnnotatedWith(PreAuthorize.class)) {
            return isPermitAllExpression(method.getAnnotationOfType(PreAuthorize.class).value());
        }
        JavaClass owner = method.getOwner();
        return owner.isAnnotatedWith(PreAuthorize.class)
                && isPermitAllExpression(owner.getAnnotationOfType(PreAuthorize.class).value());
    }

    /**
     * Whether a SpEL authorization expression lets an unauthenticated caller through.
     *
     * <p>Anything mentioning {@code permitAll} or {@code isAnonymous} counts, not only a bare
     * {@code permitAll()}. A compound grant such as {@code "permitAll() or hasRole('ADMIN')"}
     * is reachable without authentication by its first term, and reading it as protected
     * because it is not the exact string would quietly shrink what this rule looks at, which
     * is the failure mode a ratchet can least afford. Both {@code permitAll} and
     * {@code permitAll()} are valid, and whitespace is free.
     *
     * @param expression The {@code @PreAuthorize} value.
     * @return True where an unauthenticated caller can reach the handler.
     */
    static boolean isPermitAllExpression(String expression) {
        String normalized = expression.replaceAll("\\s", "");
        return normalized.contains("permitAll") || normalized.contains("isAnonymous");
    }

    /**
     * The call graph out of a public handler, and the three hops a plain call graph does not
     * have. Built once per rule run because the listener index is the same for every endpoint.
     *
     * <p>Package-private, and driven directly by {@link PublicEndpointTenantExposureRuleTest}
     * over a planted set of violations. A gate nobody has watched fail is a gate nobody knows
     * works, and every hop this class follows was added because the codebase already uses it,
     * so each one is worth a test that shows it biting.
     */
    static final class Reachability {

        private final JavaClasses classes;

        /**
         * Listener methods by the event type they take, {@code @Async} ones included.
         *
         * <p>The asynchronous ones are indexed even though they are never followed, because
         * knowing that an event has listeners is what tells an event whose only listener runs
         * on another thread apart from an event whose type could not be worked out at all.
         * The first has no edge; the second falls back to every listener.
         */
        private final Map<JavaClass, List<JavaMethod>> listenersByEventType = new LinkedHashMap<>();

        /**
         * Listeners that declare no parameter, so no constructed event can be matched against
         * them. Always a candidate, because the alternative is to drop them silently.
         */
        private final List<JavaMethod> untypedListeners = new ArrayList<>();

        Reachability(JavaClasses classes) {
            this.classes = classes;
            indexListeners();
        }

        /**
         * Walks out of a handler until it reaches a read of tenant data, or runs out of graph.
         *
         * <p>Breadth first, so the reported path is the shortest one, which is the one that
         * reads as an explanation.
         *
         * @param endpoint The public handler to walk out from.
         * @return The call path from the handler to the read, or empty where there is none.
         */
        Optional<List<String>> pathToTenantData(JavaMethod endpoint) {
            Map<JavaCodeUnit, JavaCodeUnit> arrivedFrom = new LinkedHashMap<>();
            Map<JavaCodeUnit, String> arrivedVia = new LinkedHashMap<>();
            Set<JavaCodeUnit> seen = new HashSet<>();
            Deque<JavaCodeUnit> queue = new ArrayDeque<>();

            seen.add(endpoint);
            queue.add(endpoint);

            while (!queue.isEmpty()) {
                JavaCodeUnit current = queue.poll();
                for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                    JavaClass targetOwner = call.getTargetOwner();

                    String read = tenantDataRead(targetOwner, call);
                    if (read != null) {
                        return Optional.of(describePath(endpoint, current, arrivedFrom, arrivedVia, read));
                    }

                    if (isEventPublication(targetOwner, call)) {
                        for (JavaMethod listener : listenersReachableFrom(current)) {
                            enqueue(listener, current, "on the event published above",
                                    seen, queue, arrivedFrom, arrivedVia);
                        }
                        continue;
                    }

                    if (!isOurs(targetOwner)) {
                        continue;
                    }
                    Optional<JavaMethod> called = call.getTarget().resolveMember();
                    if (called.isEmpty()) {
                        continue;
                    }
                    enqueue(called.get(), current, null, seen, queue, arrivedFrom, arrivedVia);
                    for (JavaMethod implementation : implementationsOf(called.get())) {
                        enqueue(implementation, current,
                                "implementing " + called.get().getOwner().getSimpleName(),
                                seen, queue, arrivedFrom, arrivedVia);
                    }
                }
            }
            return Optional.empty();
        }

        private void enqueue(JavaMethod next,
                             JavaCodeUnit from,
                             String via,
                             Set<JavaCodeUnit> seen,
                             Deque<JavaCodeUnit> queue,
                             Map<JavaCodeUnit, JavaCodeUnit> arrivedFrom,
                             Map<JavaCodeUnit, String> arrivedVia) {
            if (!seen.add(next)) {
                return;
            }
            arrivedFrom.put(next, from);
            if (via != null) {
                arrivedVia.put(next, via);
            }
            queue.add(next);
        }

        /**
         * What a call reads, or null where it reads nothing this rule cares about.
         *
         * <p>Three kinds of read, and they are not interchangeable. A tenant-scoped repository
         * is decided from its own declaration, so the verdict is exact. An
         * {@link EntityManager} query or a {@link JdbcOperations} call is hand-written SQL
         * whose table is a string the bytecode does not carry, so it is reported whatever it
         * touches: an unauthenticated request that has got as far as raw SQL is worth the
         * argument even when that particular statement is harmless.
         */
        private String tenantDataRead(JavaClass targetOwner, JavaMethodCall call) {
            if (isTenantScopedRepository(targetOwner)) {
                return name(targetOwner, call.getName());
            }
            if (targetOwner.isAssignableTo(EntityManager.class)
                    && ENTITY_MANAGER_READS.contains(call.getName())) {
                return name(targetOwner, call.getName());
            }
            if (targetOwner.isAssignableTo(JdbcOperations.class) && isJdbcStatement(call.getName())) {
                return name(targetOwner, call.getName());
            }
            return null;
        }

        private static boolean isJdbcStatement(String method) {
            return method.startsWith("query") || method.equals("update")
                    || method.equals("batchUpdate") || method.equals("execute");
        }

        private static boolean isEventPublication(JavaClass targetOwner, JavaMethodCall call) {
            return "publishEvent".equals(call.getName())
                    && targetOwner.isAssignableTo(ApplicationEventPublisher.class);
        }

        /**
         * The listeners a publishing method can reach.
         *
         * <p>The event type is taken from what the publishing method constructs, which is how
         * every publication in this codebase is written and the only thing the bytecode offers:
         * the call itself is erased to {@code publishEvent(Object)}. Where nothing constructed
         * there matches a listener, every listener is a candidate rather than none, so a
         * publication built somewhere else over-reports instead of disappearing.
         */
        private List<JavaMethod> listenersReachableFrom(JavaCodeUnit publisher) {
            List<JavaMethod> matched = new ArrayList<>(untypedListeners);
            boolean eventTypeIdentified = false;
            for (JavaConstructorCall constructorCall : publisher.getConstructorCallsFromSelf()) {
                String constructed = constructorCall.getTargetOwner().getName();
                for (Map.Entry<JavaClass, List<JavaMethod>> entry : listenersByEventType.entrySet()) {
                    if (!entry.getKey().isAssignableFrom(constructed)) {
                        continue;
                    }
                    eventTypeIdentified = true;
                    entry.getValue().stream().filter(Reachability::runsOnTheRequestThread)
                            .forEach(matched::add);
                }
            }
            if (eventTypeIdentified) {
                return matched;
            }
            List<JavaMethod> everyListener = new ArrayList<>(untypedListeners);
            listenersByEventType.values().forEach(listeners -> listeners.stream()
                    .filter(Reachability::runsOnTheRequestThread).forEach(everyListener::add));
            return everyListener;
        }

        /**
         * Whether a listener runs inside the request that published the event.
         *
         * <p>The owner is checked as well as the method, because Spring applies a type-level
         * {@code @Async} to every method the type declares. Reading only the method would put
         * a listener that never touches the request thread back into the reported path, and a
         * gate that reports work the load boundary already refuses is a gate people learn to
         * ignore.
         */
        private static boolean runsOnTheRequestThread(JavaMethod listener) {
            return !listener.isAnnotatedWith(Async.class)
                    && !listener.getOwner().isAnnotatedWith(Async.class);
        }

        private void indexListeners() {
            for (JavaClass javaClass : classes) {
                for (JavaMethod method : javaClass.getMethods()) {
                    if (!method.isMetaAnnotatedWith(EventListener.class)) {
                        continue;
                    }
                    List<JavaClass> parameters = method.getRawParameterTypes();
                    if (parameters.isEmpty()) {
                        if (runsOnTheRequestThread(method)) {
                            untypedListeners.add(method);
                        }
                    } else {
                        listenersByEventType
                                .computeIfAbsent(parameters.get(0), unused -> new ArrayList<>())
                                .add(method);
                    }
                }
            }
        }

        /**
         * Every implementation of an interface or abstract method in the imported graph.
         *
         * <p>A call through an interface resolves to the interface's own declaration, which has
         * no body, so without this the walk stops one hop short of the class that does the
         * reading. Nothing on a public path is injected by interface today, which is exactly
         * why it is worth closing before something is.
         */
        private List<JavaMethod> implementationsOf(JavaMethod method) {
            JavaClass owner = method.getOwner();
            if (!owner.isInterface() && !method.getModifiers().contains(JavaModifier.ABSTRACT)) {
                return List.of();
            }
            String[] parameters = method.getRawParameterTypes().stream()
                    .map(JavaClass::getName)
                    .toArray(String[]::new);
            List<JavaMethod> implementations = new ArrayList<>();
            for (JavaClass subtype : owner.getAllSubclasses()) {
                subtype.tryGetMethod(method.getName(), parameters)
                        .filter(candidate -> !candidate.getModifiers().contains(JavaModifier.ABSTRACT))
                        .ifPresent(implementations::add);
            }
            return implementations;
        }
    }

    /** The {@link EntityManager} methods that put a statement on the wire. */
    private static final Set<String> ENTITY_MANAGER_READS = Set.of(
            "createQuery", "createNativeQuery", "createNamedQuery", "createStoredProcedureQuery",
            "createNamedStoredProcedureQuery", "find", "getReference", "merge", "persist",
            "remove", "refresh", "lock");

    /**
     * Whether a call lands on a Spring Data repository that deals in tenant-scoped rows.
     *
     * <p>Decided from the repository's own declaration rather than from the call: the domain type
     * it is parameterised on, and failing that the return type of anything it declares.
     * {@code save} and friends are generic and their signatures are erased at the call site, so
     * the call itself cannot answer this.
     *
     * @param javaClass The owner of the called method.
     * @return True where the class is a repository over a {@link TenantScopedEntity}.
     */
    private static boolean isTenantScopedRepository(JavaClass javaClass) {
        if (!javaClass.isAssignableTo(Repository.class)) {
            return false;
        }
        return TENANT_SCOPED_REPOSITORIES.computeIfAbsent(javaClass.getName(),
                unused -> dealsInTenantScopedRows(javaClass.reflect()));
    }

    private static boolean dealsInTenantScopedRows(Class<?> repository) {
        for (Type genericInterface : repository.getGenericInterfaces()) {
            if (domainTypeIsTenantScoped(genericInterface)) {
                return true;
            }
        }
        // A repository that hides its domain type behind another generic interface still gives
        // itself away in what it returns. Cheaper than resolving type variables up the hierarchy
        // and it fails towards reporting rather than towards missing.
        for (Method method : repository.getDeclaredMethods()) {
            if (mentionsTenantScopedType(method.getGenericReturnType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a repository supertype is parameterised on a tenant-scoped entity.
     *
     * <p>Only the first type argument is read, which is where every Spring Data repository puts
     * its domain type; the second is the id. A non-parameterised supertype is followed upwards so
     * an intermediate {@code interface Base extends JpaRepository<X, Long>} is still resolved.
     *
     * @param supertype A generic interface of the repository.
     * @return True where the repository reads or writes tenant-scoped rows through that
     *         supertype.
     */
    private static boolean domainTypeIsTenantScoped(Type supertype) {
        if (supertype instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw
                && Repository.class.isAssignableFrom(raw)) {
            Type[] arguments = parameterized.getActualTypeArguments();
            return arguments.length > 0 && mentionsTenantScopedType(arguments[0]);
        }
        if (supertype instanceof Class<?> raw && Repository.class.isAssignableFrom(raw)) {
            for (Type next : raw.getGenericInterfaces()) {
                if (domainTypeIsTenantScoped(next)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a type is, contains or is bounded by a tenant-scoped entity. Unwraps the containers
     * a repository method returns ({@code Optional}, {@code List}, {@code Page}, {@code Slice},
     * arrays) without needing to know which is which.
     *
     * @param type The type to inspect.
     * @return True where a {@link TenantScopedEntity} appears anywhere in it.
     */
    private static boolean mentionsTenantScopedType(Type type) {
        if (type instanceof Class<?> raw) {
            return TenantScopedEntity.class.isAssignableFrom(raw)
                    || (raw.isArray() && mentionsTenantScopedType(raw.getComponentType()));
        }
        if (type instanceof ParameterizedType parameterized) {
            if (mentionsTenantScopedType(parameterized.getRawType())) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (mentionsTenantScopedType(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType array) {
            return mentionsTenantScopedType(array.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                if (mentionsTenantScopedType(bound)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                if (mentionsTenantScopedType(bound)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> describePath(JavaMethod endpoint, JavaCodeUnit lastHop,
                                             Map<JavaCodeUnit, JavaCodeUnit> arrivedFrom,
                                             Map<JavaCodeUnit, String> arrivedVia,
                                             String read) {
        List<String> path = new ArrayList<>();
        path.add(read);
        for (JavaCodeUnit step = lastHop; step != null && !step.equals(endpoint); step = arrivedFrom.get(step)) {
            String via = arrivedVia.get(step);
            path.add(0, via == null
                    ? name(step.getOwner(), step.getName())
                    : name(step.getOwner(), step.getName()) + " (" + via + ")");
        }
        path.add(0, name(endpoint.getOwner(), endpoint.getName()));
        return path;
    }

    private static String name(JavaClass owner, String member) {
        return owner.getSimpleName() + "." + member;
    }

    private static boolean isOurs(JavaClass javaClass) {
        return javaClass.getName().startsWith(PRODUCTION_PACKAGE);
    }
}
