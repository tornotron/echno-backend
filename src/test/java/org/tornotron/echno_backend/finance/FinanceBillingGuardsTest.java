package org.tornotron.echno_backend.finance;

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
import org.tornotron.echno_backend.finance.construction.web.ConstructionInvoiceControllerWeb;
import org.tornotron.echno_backend.finance.construction.web.ConstructionPaymentControllerWeb;
import org.tornotron.echno_backend.finance.invoice.web.InvoiceControllerWeb;
import org.tornotron.echno_backend.finance.payment.web.PaymentControllerWeb;

/**
 * Every customer and construction invoice and payment handler asks the access matrix for the finance
 * action it performs, and nothing else: reads need read, raising or recording a document needs create,
 * changing its state needs edit, and issuing, approving or verifying needs approve. A new handler fails
 * here until it is given its row.
 */
class FinanceBillingGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("InvoiceControllerWeb#createDraft", "create"),
            Map.entry("InvoiceControllerWeb#list", "read"),
            Map.entry("InvoiceControllerWeb#get", "read"),
            Map.entry("InvoiceControllerWeb#issue", "approve"),
            Map.entry("InvoiceControllerWeb#cancel", "edit"),
            Map.entry("PaymentControllerWeb#record", "create"),
            Map.entry("PaymentControllerWeb#get", "read"),
            Map.entry("ConstructionInvoiceControllerWeb#create", "create"),
            Map.entry("ConstructionInvoiceControllerWeb#get", "read"),
            Map.entry("ConstructionInvoiceControllerWeb#downloadPdf", "read"),
            Map.entry("ConstructionInvoiceControllerWeb#list", "read"),
            Map.entry("ConstructionInvoiceControllerWeb#update", "edit"),
            Map.entry("ConstructionInvoiceControllerWeb#submit", "edit"),
            Map.entry("ConstructionInvoiceControllerWeb#approve", "approve"),
            Map.entry("ConstructionInvoiceControllerWeb#cancel", "edit"),
            Map.entry("ConstructionInvoiceControllerWeb#recordPayment", "create"),
            Map.entry("ConstructionPaymentControllerWeb#create", "create"),
            Map.entry("ConstructionPaymentControllerWeb#get", "read"),
            Map.entry("ConstructionPaymentControllerWeb#list", "read"),
            Map.entry("ConstructionPaymentControllerWeb#update", "edit"),
            Map.entry("ConstructionPaymentControllerWeb#verify", "approve"),
            Map.entry("ConstructionPaymentControllerWeb#cancel", "edit"));

    @Test
    void everyHandlerNeedsTheFinanceActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(FinanceBillingGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('finance', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(InvoiceControllerWeb.class, PaymentControllerWeb.class,
                        ConstructionInvoiceControllerWeb.class, ConstructionPaymentControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
