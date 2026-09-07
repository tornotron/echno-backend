package org.tornotron.echno_backend.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteController;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteControllerWeb;
import org.tornotron.echno_backend.indent.IndentController;
import org.tornotron.echno_backend.indent.IndentControllerWeb;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumptionController;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumptionControllerWeb;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderController;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderControllerWeb;
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

    /** The five document controllers that carry the org-role guard, web twins and the one mobile. */
    private static final List<Class<?>> ROLE_GUARDED = List.of(
            GoodsReceivedNoteControllerWeb.class,
            PurchaseOrderControllerWeb.class,
            PurchaseOrderController.class,
            IndentControllerWeb.class,
            SiteTransferControllerWeb.class,
            MaterialConsumptionControllerWeb.class);

    /**
     * The mobile twins that are guarded on flat {@code resource:scope} authorities instead. Nothing
     * in the realm mints those, so they refuse every caller including a system admin. That is the
     * phantom-guard defect of #684 rather than this decision, and repairing it is a separate change
     * with its own blast radius. Pinned here so the omission reads as deliberate.
     */
    private static final List<Class<?>> AUTHORITY_GUARDED = List.of(
            GoodsReceivedNoteController.class,
            IndentController.class,
            SiteTransferController.class,
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

    @Test
    void theAuthorityGuardedMobileTwinsWereLeftAlone() {
        List<String> touched = new ArrayList<>();
        for (Class<?> controller : AUTHORITY_GUARDED) {
            for (Method method : requestMappedMethods(controller)) {
                if (guardOf(method).contains("orgSecurity")) {
                    touched.add(describe(controller, method));
                }
            }
        }
        assertThat(touched).isEmpty();
    }

    /** Guards against the whole thing passing because reflection found nothing to look at. */
    @Test
    void theEndpointsUnderTestWereActuallyFound() {
        int total = 0;
        for (Class<?> controller : ROLE_GUARDED) {
            total += requestMappedMethods(controller).size();
        }
        assertThat(total).isEqualTo(56);
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
