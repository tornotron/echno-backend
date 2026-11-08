package org.tornotron.echno_backend.purchaseOrder;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemController;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemControllerWeb;

/**
 * Every purchase order and line handler asks the access matrix for the action it performs, and
 * nothing else: reads need read, raising an order or adding a line needs create, changing either
 * needs edit, a status change needs approve and removing a line needs delete. A new handler fails
 * here until it is given its row.
 */
class PurchaseOrderGuardsTest {

    /** Every handler, by controller and name, and the action it needs. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            entry("PurchaseOrderControllerWeb.createPurchaseOrder", "create"),
            entry("PurchaseOrderControllerWeb.getPurchaseOrderById", "read"),
            entry("PurchaseOrderControllerWeb.readStatusHistory", "read"),
            entry("PurchaseOrderControllerWeb.getAllPurchaseOrders", "read"),
            entry("PurchaseOrderControllerWeb.getAllPurchaseOrdersPaginated", "read"),
            entry("PurchaseOrderControllerWeb.getPurchaseOrdersByVendor", "read"),
            entry("PurchaseOrderControllerWeb.getPurchaseOrdersByIndent", "read"),
            entry("PurchaseOrderControllerWeb.getPurchaseOrdersByStatus", "read"),
            entry("PurchaseOrderControllerWeb.updatePurchaseOrder", "edit"),
            entry("PurchaseOrderControllerWeb.updatePurchaseOrderStatus", "approve"),
            entry("PurchaseOrderController.createPurchaseOrder", "create"),
            entry("PurchaseOrderController.getPurchaseOrderById", "read"),
            entry("PurchaseOrderController.getAllPurchaseOrders", "read"),
            entry("PurchaseOrderController.getAllPurchaseOrdersPaginated", "read"),
            entry("PurchaseOrderController.getPurchaseOrdersByVendor", "read"),
            entry("PurchaseOrderController.getPurchaseOrdersByIndent", "read"),
            entry("PurchaseOrderController.getPurchaseOrdersByStatus", "read"),
            entry("PurchaseOrderController.updatePurchaseOrder", "edit"),
            entry("PurchaseOrderController.updatePurchaseOrderStatus", "approve"),
            entry("PurchaseOrderItemControllerWeb.createPurchaseOrderItem", "create"),
            entry("PurchaseOrderItemControllerWeb.getPurchaseOrderItemById", "read"),
            entry("PurchaseOrderItemControllerWeb.getAllPurchaseOrderItems", "read"),
            entry("PurchaseOrderItemControllerWeb.getPurchaseOrderItemsPaginated", "read"),
            entry("PurchaseOrderItemControllerWeb.getItemsByPurchaseOrderId", "read"),
            entry("PurchaseOrderItemControllerWeb.getItemsByMaterialId", "read"),
            entry("PurchaseOrderItemControllerWeb.updatePurchaseOrderItem", "edit"),
            entry("PurchaseOrderItemControllerWeb.deletePurchaseOrderItem", "delete"),
            entry("PurchaseOrderItemController.createPurchaseOrderItem", "create"),
            entry("PurchaseOrderItemController.getPurchaseOrderItemById", "read"),
            entry("PurchaseOrderItemController.getAllPurchaseOrderItems", "read"),
            entry("PurchaseOrderItemController.getPurchaseOrderItemsPaginated", "read"),
            entry("PurchaseOrderItemController.getItemsByPurchaseOrderId", "read"),
            entry("PurchaseOrderItemController.getItemsByMaterialId", "read"),
            entry("PurchaseOrderItemController.getItemsByPurchaseOrderIdPaginated", "read"),
            entry("PurchaseOrderItemController.getItemsByMaterialIdPaginated", "read"),
            entry("PurchaseOrderItemController.updatePurchaseOrderItem", "edit"),
            entry("PurchaseOrderItemController.deletePurchaseOrderItem", "delete"));

    @Test
    void everyHandlerNeedsThePurchaseOrdersActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(PurchaseOrderGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('purchase-orders', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "." + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(PurchaseOrderControllerWeb.class, PurchaseOrderController.class,
                        PurchaseOrderItemControllerWeb.class, PurchaseOrderItemController.class)
                .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
