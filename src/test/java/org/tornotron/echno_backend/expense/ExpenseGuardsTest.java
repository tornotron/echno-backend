package org.tornotron.echno_backend.expense;

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
import org.tornotron.echno_backend.receipt.ReceiptControllerWeb;

/**
 * Every expense and receipt handler asks the access matrix for the expenses action it performs, and
 * nothing else: reads need read, recording one needs create, a change needs edit and a removal needs
 * delete. A new handler fails here until it is given its row.
 */
class ExpenseGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("ExpenseControllerWeb#readAllExpenses", "read"),
            Map.entry("ExpenseControllerWeb#readAllExpensesPaginated", "read"),
            Map.entry("ExpenseControllerWeb#readAnExpense", "read"),
            Map.entry("ExpenseControllerWeb#createExpense", "create"),
            Map.entry("ExpenseControllerWeb#updateExpense", "edit"),
            Map.entry("ExpenseControllerWeb#deleteExpense", "delete"),
            Map.entry("ReceiptControllerWeb#readAllReceipts", "read"),
            Map.entry("ReceiptControllerWeb#readAllReceiptsPaginated", "read"),
            Map.entry("ReceiptControllerWeb#readAReceipt", "read"),
            Map.entry("ReceiptControllerWeb#createReceipt", "create"),
            Map.entry("ReceiptControllerWeb#updateReceipt", "edit"),
            Map.entry("ReceiptControllerWeb#deleteReceipt", "delete"));

    @Test
    void everyHandlerNeedsTheExpensesActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(ExpenseGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('expenses', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(ExpenseControllerWeb.class, ReceiptControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
