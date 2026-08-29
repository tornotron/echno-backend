package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import org.springframework.data.repository.Repository;
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
 * with an empty grandfather set, so {@code permitAll()} is the complete and authoritative marker
 * of an endpoint whose author intended it to be reachable unauthenticated. The security config's
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
 * ({@code /user/web}, {@code /billing/**}, and the no-membership state). Those already read
 * tenant-scoped rows knowingly, scoped by the caller's own user id rather than by the tenant
 * layer, so a rule over them would be an allowlist of its own contents and would say nothing.
 *
 * <p>Reachability is computed over method calls only and only through this codebase's own
 * classes, the same walk {@link MapperDatabaseAccessTest} uses, for the same reason: neither
 * hazard names its repository directly, and a direct-call rule would pass the very code it exists
 * to prevent. Breadth first, so the reported path is the shortest one.
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

        Map<String, List<String>> offenders = new LinkedHashMap<>();
        for (JavaMethod endpoint : publicEndpoints) {
            pathToTenantScopedRepository(endpoint).ifPresent(path ->
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
    private static List<JavaMethod> publicEndpoints(JavaClasses productionClasses) {
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
     * Whether a SpEL authorization expression grants everyone. Both {@code permitAll} and
     * {@code permitAll()} are valid and both appear in the wild, and whitespace is free.
     *
     * @param expression The {@code @PreAuthorize} value.
     * @return True where the expression is a bare permit-all.
     */
    private static boolean isPermitAllExpression(String expression) {
        String normalized = expression.replaceAll("\\s", "");
        return "permitAll".equals(normalized) || "permitAll()".equals(normalized);
    }

    /**
     * Walks the method-call graph out of a handler, looking for a call on a repository whose rows
     * are tenant-scoped.
     *
     * <p>Breadth first, so the reported path is the shortest one, which is the one that reads as
     * an explanation. Only calls landing in this codebase are followed: a framework method is a
     * leaf, either a repository call or of no interest.
     *
     * @param endpoint The public handler to walk out from.
     * @return The call path from the handler to the tenant-scoped read, or empty where there is
     *         none.
     */
    private static Optional<List<String>> pathToTenantScopedRepository(JavaMethod endpoint) {
        Map<JavaCodeUnit, JavaCodeUnit> arrivedFrom = new LinkedHashMap<>();
        Set<JavaCodeUnit> seen = new HashSet<>();
        Deque<JavaCodeUnit> queue = new ArrayDeque<>();

        seen.add(endpoint);
        queue.add(endpoint);

        while (!queue.isEmpty()) {
            JavaCodeUnit current = queue.poll();
            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                JavaClass targetOwner = call.getTargetOwner();
                if (isTenantScopedRepository(targetOwner)) {
                    return Optional.of(describePath(endpoint, current, arrivedFrom, call));
                }
                if (!isOurs(targetOwner)) {
                    continue;
                }
                Optional<JavaMethod> called = call.getTarget().resolveMember();
                if (called.isEmpty() || !seen.add(called.get())) {
                    continue;
                }
                arrivedFrom.put(called.get(), current);
                queue.add(called.get());
            }
        }
        return Optional.empty();
    }

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
                                             JavaMethodCall repositoryCall) {
        List<String> path = new ArrayList<>();
        path.add(name(repositoryCall.getTargetOwner(), repositoryCall.getName()));
        for (JavaCodeUnit step = lastHop; step != null && !step.equals(endpoint); step = arrivedFrom.get(step)) {
            path.add(0, name(step.getOwner(), step.getName()));
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
