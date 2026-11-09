package org.tornotron.echno_backend.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumptionController;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumptionControllerWeb;
import org.tornotron.echno_backend.siteTransfer.SiteTransferController;
import org.tornotron.echno_backend.siteTransfer.SiteTransferControllerWeb;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read side of the five procurement documents moved and the write side did not, on every
 * endpoint rather than on the handful a slice can exercise.
 *
 * <p>A web-slice test proves a status code for one request. It cannot easily prove the negative
 * this change rests on, that no write was widened along with the reads, because a write is guarded
 * behind argument binding: {@code @PreAuthorize} is evaluated when the handler is invoked, so an
 * endpoint that validates its body answers 400 to a deliberately empty one and says nothing about
 * its guard. Reading the annotation is the direct way to ask, and it covers the endpoints a slice
 * would have skipped as well as the next one somebody adds.
 *
 * <p>The rule: on these controllers a GET admits {@code project-manager} beside
 * {@code system-admin}, and everything else admits {@code system-admin} alone. Reading a purchase
 * order is not raising one.
 */
class ProcurementDocumentGuardSplitTest {

    private static final String PROJECT_MANAGER = "project-manager";

    /**
     * The document controllers that carry the org-role guard. The web indent controller left this
     * list when its reads moved onto the access matrix, where {@code IndentGuardsTest} and
     * {@code IndentAccessEquivalenceIT} hold it to the same split. Both purchase order controllers
     * left it the same way, for {@code PurchaseOrderGuardsTest} and
     * {@code PurchaseOrderAccessEquivalenceIT}, and both goods receipt controllers for
     * {@code GoodsReceivedNoteGuardsTest} and {@code GoodsReceivedNoteAccessEquivalenceIT}. The mobile twins were guarded on flat
     * {@code resource:scope} authorities nothing in the realm mints, until development aligned them
     * with their web twins, so the remaining ones carry the same split. The mobile indent controller
     * follows its web twin onto the matrix ({@code MobileWebGuardParityTest}).
     */
    private static final List<Class<?>> ROLE_GUARDED = List.of(
            SiteTransferControllerWeb.class,
            SiteTransferController.class,
            MaterialConsumptionControllerWeb.class,
            MaterialConsumptionController.class);

    @Test
    void everyDocumentReadAdmitsTheProjectManager() {
        List<String> readsThatDoNot = new ArrayList<>();
        for (Class<?> controller : ROLE_GUARDED) {
            for (Method method : requestMappedMethods(controller)) {
                if (isRead(method) && !guardOf(method).contains(PROJECT_MANAGER)) {
                    readsThatDoNot.add(describe(controller, method));
                }
            }
        }
        assertThat(readsThatDoNot).isEmpty();
    }

    @Test
    void noDocumentWriteAdmitsTheProjectManager() {
        List<String> writesThatDo = new ArrayList<>();
        for (Class<?> controller : ROLE_GUARDED) {
            for (Method method : requestMappedMethods(controller)) {
                if (!isRead(method) && guardOf(method).contains(PROJECT_MANAGER)) {
                    writesThatDo.add(describe(controller, method));
                }
            }
        }
        assertThat(writesThatDo).isEmpty();
    }

    /** A guard that stopped mentioning system-admin would be a narrowing, not a widening. */
    @Test
    void everyDocumentEndpointStillAdmitsTheSystemAdmin() {
        List<String> withoutSystemAdmin = new ArrayList<>();
        for (Class<?> controller : ROLE_GUARDED) {
            for (Method method : requestMappedMethods(controller)) {
                if (!guardOf(method).contains("system-admin")) {
                    withoutSystemAdmin.add(describe(controller, method));
                }
            }
        }
        assertThat(withoutSystemAdmin).isEmpty();
    }

    /** Guards against the whole thing passing because reflection found nothing to look at. */
    @Test
    void theEndpointsUnderTestWereActuallyFound() {
        int total = 0;
        for (Class<?> controller : ROLE_GUARDED) {
            total += requestMappedMethods(controller).size();
        }
        // 57 since the site transfer picker for asset lines (GET /sendable-assets), less the eleven
        // web indent endpoints, the nineteen purchase order endpoints and the seven web goods receipt
        // endpoints that moved onto the access matrix, plus the 18 on the mobile twins of the other
        // two (site transfer 10, material consumption 8).
        assertThat(total).isEqualTo(38);
    }

    private static List<Method> requestMappedMethods(Class<?> controller) {
        List<Method> methods = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (mappingAnnotationPresent(method)) {
                methods.add(method);
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    private static boolean mappingAnnotationPresent(Method method) {
        for (var annotation : method.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(RequestMapping.class)
                    || annotation.annotationType() == RequestMapping.class) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRead(Method method) {
        return method.isAnnotationPresent(GetMapping.class);
    }

    private static String guardOf(Method method) {
        PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
        return guard == null ? "" : guard.value();
    }

    private static String describe(Class<?> controller, Method method) {
        String verb = method.isAnnotationPresent(GetMapping.class) ? "GET"
                : method.isAnnotationPresent(PostMapping.class) ? "POST"
                : method.isAnnotationPresent(PatchMapping.class) ? "PATCH"
                : method.isAnnotationPresent(PutMapping.class) ? "PUT"
                : method.isAnnotationPresent(DeleteMapping.class) ? "DELETE"
                : "MAPPED";
        return verb + " " + controller.getSimpleName() + "." + method.getName()
                + " guarded by " + guardOf(method);
    }
}
