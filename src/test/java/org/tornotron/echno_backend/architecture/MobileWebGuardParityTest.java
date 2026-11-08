package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mobile endpoint and its {@code /web} twin are authorized by the same expression.
 *
 * <p>Most resources have a controller on the tenant API, {@code /api/v1/<resource>}, which the
 * mobile apps call with an {@code X-Organization-Id} header, and a twin on
 * {@code /api/v1/<resource>/web} over the same service. The two used to disagree: the mobile side
 * named {@code resource:scope} authorities the realm cannot issue, so it refused every caller
 * while the web side worked. They now carry one expression per operation, and this test keeps it
 * that way, so a guard changed on one side and not the other fails the build.
 *
 * <p>A pair is a controller {@code XController} and {@code XControllerWeb} (or
 * {@code XWebController}) in the same package. Two handlers are the same operation when they share
 * a method name, HTTP verb and path relative to their controller. Their {@code @PreAuthorize}
 * values are compared with whitespace removed, since the twins are formatted independently.
 *
 * <p>A pair meant to differ belongs in {@link #INTENTIONAL_DIFFERENCES} with its reason. It is
 * empty: no difference is intended today.
 */
class MobileWebGuardParityTest {

    /** {@code Controller.handler} of a mobile endpoint whose guard is meant to differ, with why. */
    private static final Map<String, String> INTENTIONAL_DIFFERENCES = Map.of();

    /** The controllers aligned in the mobile repair, which must each be among the pairs compared. */
    private static final Set<String> ALIGNED_IN_THE_MOBILE_REPAIR = Set.of(
            "CategoryController", "GoodsReceivedNoteController", "IndentController",
            "IndentItemController", "InventoryTransactionController", "MaterialConsumptionController",
            "MaterialController", "OrganizationController", "PayableController", "ProjectController",
            "SiteTransferController", "StorageLocationController", "TaskController", "VendorController");

    private record Comparison(String mobileEndpoint, String mobileGuard, String webGuard) {}

    @Test
    void everyMobileEndpointCarriesItsWebTwinsGuard() {
        List<String> mismatches = new ArrayList<>();
        for (Comparison comparison : comparisons()) {
            boolean same = squash(comparison.mobileGuard()).equals(squash(comparison.webGuard()));
            if (!same && !INTENTIONAL_DIFFERENCES.containsKey(comparison.mobileEndpoint())) {
                mismatches.add(comparison.mobileEndpoint()
                        + "\n    mobile: " + comparison.mobileGuard()
                        + "\n    web:    " + comparison.webGuard());
            }
        }
        assertThat(mismatches).isEmpty();
    }

    /** An exception that no longer names a real difference is stale and should be removed. */
    @Test
    void everyRecordedDifferenceIsStillADifference() {
        for (Comparison comparison : comparisons()) {
            if (INTENTIONAL_DIFFERENCES.containsKey(comparison.mobileEndpoint())) {
                assertThat(squash(comparison.mobileGuard()))
                        .as(comparison.mobileEndpoint())
                        .isNotEqualTo(squash(comparison.webGuard()));
            }
        }
    }

    /** Guards against the whole thing passing because the pairing found nothing to compare. */
    @Test
    void thePairingFoundTheTwins() {
        List<Comparison> comparisons = comparisons();
        Set<String> pairedControllers = new java.util.TreeSet<>();
        for (Comparison comparison : comparisons) {
            pairedControllers.add(comparison.mobileEndpoint().substring(0, comparison.mobileEndpoint().indexOf('.')));
        }
        assertThat(comparisons).hasSizeGreaterThan(250);
        assertThat(pairedControllers).containsAll(ALIGNED_IN_THE_MOBILE_REPAIR);
    }

    private static List<Comparison> comparisons() {
        Map<String, Class<?>> byName = new HashMap<>();
        for (Class<?> controller : controllers()) {
            byName.put(controller.getName(), controller);
        }
        List<Comparison> comparisons = new ArrayList<>();
        for (Class<?> mobile : byName.values()) {
            String name = mobile.getName();
            if (!name.endsWith("Controller")) {
                continue;
            }
            Class<?> web = byName.get(name + "Web");
            if (web == null) {
                web = byName.get(name.substring(0, name.length() - "Controller".length()) + "WebController");
            }
            if (web == null) {
                continue;
            }
            Map<String, Method> webHandlers = handlers(web);
            for (Map.Entry<String, Method> handler : handlers(mobile).entrySet()) {
                Method twin = webHandlers.get(handler.getKey());
                if (twin != null) {
                    comparisons.add(new Comparison(
                            mobile.getSimpleName() + "." + handler.getValue().getName(),
                            guardOf(handler.getValue(), mobile),
                            guardOf(twin, web)));
                }
            }
        }
        return comparisons;
    }

    /** Request-mapped handlers keyed by method name, verbs and path relative to the controller. */
    private static Map<String, Method> handlers(Class<?> controller) {
        Map<String, Method> handlers = new HashMap<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] verbs = Arrays.stream(mapping.method()).map(Enum::name).sorted().toArray(String[]::new);
            String path = mapping.path().length == 0 ? "" : mapping.path()[0].replaceFirst("^/", "");
            handlers.put(method.getName() + " " + String.join(",", verbs) + " " + path, method);
        }
        return handlers;
    }

    private static String guardOf(Method method, Class<?> controller) {
        PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (guard == null) {
            guard = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
        }
        return guard == null ? "(none)" : guard.value();
    }

    private static String squash(String expression) {
        return expression.replaceAll("\\s+", "");
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents("org.tornotron.echno_backend");
        return candidates.stream()
                .map(BeanDefinition::getBeanClassName)
                .sorted()
                .map(MobileWebGuardParityTest::load)
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
