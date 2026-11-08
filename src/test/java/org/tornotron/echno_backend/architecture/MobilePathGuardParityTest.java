package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.asset.AssetController;
import org.tornotron.echno_backend.asset.AssetControllerWeb;
import org.tornotron.echno_backend.common.controller.AttachmentController;
import org.tornotron.echno_backend.common.controller.AttachmentControllerWeb;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.expense.ExpenseController;
import org.tornotron.echno_backend.expense.ExpenseControllerWeb;
import org.tornotron.echno_backend.labour.LabourController;
import org.tornotron.echno_backend.labour.LabourControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.ChecklistTemplateController;
import org.tornotron.echno_backend.modules.inspections.web.ChecklistTemplateControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.InspectionController;
import org.tornotron.echno_backend.modules.inspections.web.InspectionControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.InspectionEventController;
import org.tornotron.echno_backend.modules.inspections.web.InspectionEventControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.NcrController;
import org.tornotron.echno_backend.modules.inspections.web.NcrControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.ObservationController;
import org.tornotron.echno_backend.modules.inspections.web.ObservationControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.ReinspectionController;
import org.tornotron.echno_backend.modules.inspections.web.ReinspectionControllerWeb;
import org.tornotron.echno_backend.receipt.ReceiptController;
import org.tornotron.echno_backend.receipt.ReceiptControllerWeb;
import org.tornotron.echno_backend.search.MobileSearchController;
import org.tornotron.echno_backend.search.SearchController;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentController;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentControllerWeb;
import org.tornotron.echno_backend.subcontract.SubContractController;
import org.tornotron.echno_backend.subcontract.SubContractControllerWeb;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mobile paths for the modules that used to be web only (#998) answer exactly as their web
 * twins do.
 *
 * <p>Each mobile controller extends its web twin. Most only set the un-suffixed class path and
 * inherit every handler; the two whose web handlers write {@code /web} into their own paths
 * override each handler with the un-suffixed path and repeat its guard. Either way this pins,
 * handler by handler, that the mobile controller serves the web twin's endpoints at the same
 * paths with {@code /web} taken out, with the same HTTP methods, under the same
 * {@code @PreAuthorize} text and behind the same paywall. An override that drifts from its web
 * guard, or a handler added to a web twin that does not reach the mobile path, fails here.
 */
class MobilePathGuardParityTest {

    static Stream<Arguments> pairs() {
        return Stream.of(
                Arguments.of(ExpenseController.class, ExpenseControllerWeb.class),
                Arguments.of(ReceiptController.class, ReceiptControllerWeb.class),
                Arguments.of(AssetController.class, AssetControllerWeb.class),
                Arguments.of(AttachmentController.class, AttachmentControllerWeb.class),
                Arguments.of(LabourController.class, LabourControllerWeb.class),
                Arguments.of(SubContractController.class, SubContractControllerWeb.class),
                Arguments.of(StockAdjustmentController.class, StockAdjustmentControllerWeb.class),
                Arguments.of(MobileSearchController.class, SearchController.class),
                Arguments.of(ChecklistTemplateController.class, ChecklistTemplateControllerWeb.class),
                Arguments.of(InspectionController.class, InspectionControllerWeb.class),
                Arguments.of(NcrController.class, NcrControllerWeb.class),
                Arguments.of(ObservationController.class, ObservationControllerWeb.class),
                Arguments.of(InspectionEventController.class, InspectionEventControllerWeb.class),
                Arguments.of(ReinspectionController.class, ReinspectionControllerWeb.class));
    }

    /** One endpoint as a client sees it: method and path, and the guard in front of it. */
    private record Endpoint(String route, String guard) {}

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    void theMobileControllerServesEveryWebEndpointWithoutWebUnderTheSameGuard(Class<?> mobile, Class<?> web) {
        assertThat(web).isAssignableFrom(mobile);

        Map<String, Endpoint> expected = endpointsOf(web, web, true);
        Map<String, Endpoint> actual = endpointsOf(mobile, web, false);

        assertThat(expected).as("%s exposes handlers", web.getSimpleName()).isNotEmpty();
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    void theMobileControllerSitsBehindTheSamePaywall(Class<?> mobile, Class<?> web) {
        RequireSubscription webGate = web.getAnnotation(RequireSubscription.class);
        RequireSubscription mobileGate = mobile.getAnnotation(RequireSubscription.class);

        if (webGate == null) {
            assertThat(mobileGate).isNull();
        } else {
            assertThat(mobileGate).as("%s declares the paywall itself", mobile.getSimpleName()).isNotNull();
            assertThat(mobileGate.feature()).isEqualTo(webGate.feature());
        }
    }

    /**
     * Every handler {@code controller} serves, keyed by Java signature. For the web twin the paths
     * are rewritten without {@code /web}, which is what the mobile controller must serve.
     */
    private static Map<String, Endpoint> endpointsOf(Class<?> controller, Class<?> web, boolean dropWeb) {
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String[] prefixes = classMapping == null || classMapping.path().length == 0
                ? new String[]{""} : classMapping.path();

        Map<String, Endpoint> endpoints = new TreeMap<>();
        for (Method declared : web.getDeclaredMethods()) {
            Method method = ClassUtils.getMostSpecificMethod(declared, controller);
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            List<String> routes = new ArrayList<>();
            String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
            for (String prefix : prefixes) {
                for (String path : paths) {
                    String full = prefix + (path.isEmpty() || path.startsWith("/") ? path : "/" + path);
                    String route = Arrays.toString(mapping.method()) + " "
                            + (dropWeb ? full.replaceFirst("/web(?=/|$)", "") : full);
                    if (!dropWeb) {
                        assertThat(full).as("%s.%s", controller.getSimpleName(), method.getName())
                                .doesNotContain("/web");
                    }
                    routes.add(route);
                }
            }
            PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
            assertThat(guard).as("%s.%s carries a guard", controller.getSimpleName(), method.getName())
                    .isNotNull();
            endpoints.put(signature(declared), new Endpoint(String.join(", ", routes), guard.value()));
        }
        return endpoints;
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes());
    }
}
