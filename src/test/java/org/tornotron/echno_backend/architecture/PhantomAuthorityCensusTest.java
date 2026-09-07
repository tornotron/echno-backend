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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A ratchet over the endpoints still guarded by an authority the realm cannot issue, so the
 * number can only come down and a new one cannot arrive unnoticed.
 *
 * <h2>What is being counted</h2>
 *
 * <p>{@code JwtAuthConverter.extractPermissions} is the only place a {@code resource:scope}
 * authority is minted, and it reads the {@code authorization} claim of an RPT. The realm defines
 * no authorization scopes: {@code ensureDefaultResource} registers a Default Resource and never
 * calls {@code setScopes}, and a permission with no scopes yields no authority. So a guard of the
 * form {@code hasAuthority('thing:action')} is never satisfied. ORed with nothing else it refuses
 * every caller; ANDed onto a working check it refuses every caller too. Established by #641,
 * #684, #710 and #716, and inventoried in #734.
 *
 * <p>The raw grep in #734 reported 168 lines containing {@code hasAuthority(}, deliberately as an
 * upper bound. That figure counts commented-out annotations, non-guard uses of the same string in
 * {@code TenantFilter} and {@code OrganizationSecurityService}, and whole modules already
 * repaired. Reading the annotations instead, on {@code development} at the time of writing, the
 * live figure was 136 guards on 17 controllers. This test counts the same way, so a
 * {@code hasAuthority} left in a comment or in prose does not move it.
 *
 * <h2>Why the remainder is left standing</h2>
 *
 * <p>Every entry below is a mobile controller whose {@code /web} twin carries the working
 * version, so nothing a user does today is blocked by one. Repairing them is not one decision
 * but three, taken per module: a dead read whose twin is alive is usually a surface that should
 * work, a dead write may still be required (the mobile leave endpoints were repaired for exactly
 * that reason), and an endpoint with no caller anywhere is a removal candidate, which is a change
 * to a published contract rather than a repair to a guard. This map is what that decision has to
 * be taken against.
 *
 * <p>Removing an entry means the controller was repaired or removed. Adding one, or raising a
 * number, means a guard nobody can satisfy has just been written, and the fix is the guard rather
 * than this file.
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
     * Controller simple name to the number of its request-mapped methods still guarded by an
     * authority the realm cannot issue. Ordered, so a failure reads as a diff.
     */
    private static final Map<String, Integer> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("CategoryController", 4),
            Map.entry("EmployeeController", 1),
            Map.entry("GoodsReceivedNoteController", 7),
            Map.entry("IndentController", 9),
            Map.entry("IndentItemController", 10),
            Map.entry("InventoryTransactionController", 13),
            Map.entry("MaterialConsumptionController", 8),
            Map.entry("MaterialController", 12),
            Map.entry("OrganizationController", 4),
            Map.entry("PayableController", 7),
            Map.entry("ProjectController", 9),
            Map.entry("SiteTransferController", 10),
            Map.entry("StorageLocationController", 6),
            Map.entry("TaskController", 5),
            Map.entry("VendorController", 23)));

    @Test
    void theRemainingPhantomGuardsAreExactlyTheOnesRecorded() {
        assertThat(census()).isEqualTo(EXPECTED);
    }

    /**
     * The Issues module and the mobile join route were the coherent subset repaired first, so
     * naming them keeps the boundary explicit: a phantom guard reappearing on one of these is a
     * regression rather than a leftover.
     */
    @Test
    void theRepairedControllersCarryNoPhantomGuardAtAll() {
        assertThat(census()).doesNotContainKeys(
                "IssueController", "IssueCommentController");
    }

    /** The single remaining employee guard is the create route, which is proposed for removal. */
    @Test
    void theOnlyEmployeeGuardLeftIsTheCreateRouteThatCannotSucceed() {
        assertThat(phantomGuardedMethodNames("EmployeeController"))
                .containsExactly("createEmployee");
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

    private static List<String> phantomGuardedMethodNames(String simpleName) {
        for (Class<?> controller : controllers()) {
            if (controller.getSimpleName().equals(simpleName)) {
                return phantomGuardedMethodNames(controller);
            }
        }
        throw new AssertionError("No controller named " + simpleName + " on the classpath");
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
