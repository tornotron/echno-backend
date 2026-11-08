package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No endpoint is guarded by an authority the realm cannot issue.
 *
 * <h2>What is being looked for</h2>
 *
 * <p>{@code JwtAuthConverter.extractPermissions} is the only place a {@code resource:scope}
 * authority is minted, and it reads the {@code authorization} claim of an RPT. The realm defines
 * no authorization scopes: {@code ensureDefaultResource} registers a Default Resource and never
 * calls {@code setScopes}, and a permission with no scopes yields no authority. So a guard of the
 * form {@code hasAuthority('thing:action')} is never satisfied. ORed with nothing else it refuses
 * every caller; ANDed onto a working check it refuses every caller too. Established by #641,
 * #684, #710 and #716, and inventoried in #734.
 *
 * <p>This used to be a ratchet pinning 128 such guards on 15 mobile controllers, left standing
 * while no client called the tenant API. The Android app does, so each was aligned with its
 * {@code /web} twin and the count is now zero. The annotations are read, not grepped, so a
 * {@code hasAuthority} left in a comment or in prose does not count.
 *
 * <p>An endpoint that is meant to stay shut says so with {@code denyAll()} and a comment. The
 * second test pins that list, so a route cannot be closed, or opened, without it showing here.
 */
class PhantomAuthorityCensusTest {

    /**
     * A bare {@code resource:scope} authority. The colon is what distinguishes it from a realm
     * role, which carries no scope and is granted by an ordinary role claim.
     *
     * <p>Both halves are deliberately anything-but-a-quote-or-colon rather than a character class.
     * {@code extractPermissions} concatenates the resource name and the scope straight out of the
     * token without validating either, so a guard naming {@code employee:read_all} would be just
     * as unsatisfiable and a tighter pattern would walk past it.
     */
    private static final Pattern PHANTOM_AUTHORITY =
            Pattern.compile("hasAuthority\\('[^':]+:[^':]+'\\)");

    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, PatchMapping.class, DeleteMapping.class);

    /**
     * The endpoints deliberately refused to every caller, as controller and handler name. Each
     * carries its reason beside the annotation.
     */
    private static final Set<String> SHUT_ON_PURPOSE = Set.of(
            "EmployeeController.createEmployee",
            "OrganizationController.batchUpdateOrganizations",
            "OrganizationController.readAllOrganizationsByCreatorId");

    @Test
    void noEndpointIsGuardedByAnAuthorityTheRealmCannotIssue() {
        assertThat(census()).isEmpty();
    }

    @Test
    void theEndpointsShutOnPurposeAreExactlyTheOnesRecorded() {
        Set<String> shut = new TreeSet<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || !isRequestMapped(method)) {
                    continue;
                }
                PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                if (guard != null && guard.value().replace(" ", "").equals("denyAll()")) {
                    shut.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(shut).isEqualTo(new TreeSet<>(SHUT_ON_PURPOSE));
    }

    /** Guards against the whole thing passing because the scan found nothing to look at. */
    @Test
    void theScanFoundTheControllers() {
        assertThat(controllers()).hasSizeGreaterThan(60);
    }

    private static Map<String, Integer> census() {
        Map<String, Integer> found = new TreeMap<>();
        for (Class<?> controller : controllers()) {
            int count = phantomGuardedMethodNames(controller).size();
            if (count > 0) {
                found.put(controller.getSimpleName(), count);
            }
        }
        return found;
    }

    private static List<String> phantomGuardedMethodNames(Class<?> controller) {
        return java.util.Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .filter(PhantomAuthorityCensusTest::isRequestMapped)
                .filter(method -> isPhantomGuarded(method, controller))
                .map(Method::getName)
                .sorted()
                .toList();
    }

    private static boolean isRequestMapped(Method method) {
        for (Class<? extends Annotation> mapping : MAPPINGS) {
            if (AnnotatedElementUtils.hasAnnotation(method, mapping)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPhantomGuarded(Method method, Class<?> controller) {
        PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (guard == null) {
            guard = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
        }
        if (guard == null) {
            return false;
        }
        Matcher matcher = PHANTOM_AUTHORITY.matcher(guard.value());
        return matcher.find();
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents("org.tornotron.echno_backend");
        return candidates.stream()
                .map(BeanDefinition::getBeanClassName)
                .sorted()
                .map(PhantomAuthorityCensusTest::load)
                .toList();
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Scanned a controller that will not load: " + className, e);
        }
    }
}
