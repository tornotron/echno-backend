package org.tornotron.echno_backend.finance.budget;

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
import org.tornotron.echno_backend.finance.bank.web.CompanyBankAccountControllerWeb;
import org.tornotron.echno_backend.finance.budget.web.BudgetAllocationControllerWeb;
import org.tornotron.echno_backend.finance.budget.web.CostCategoryControllerWeb;
import org.tornotron.echno_backend.finance.budget.web.ProjectCostControlControllerWeb;

/**
 * Every cost category, budget, cost-control and company bank account handler asks the access matrix
 * for the finance action it performs, and nothing else: reads need read, adding a head or an account
 * needs create, a change or a deactivation needs edit, removing an allocation needs delete, and seeding
 * the default heads needs configure. A new handler fails here until it is given its row.
 */
class BudgetAndBankGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("CostCategoryControllerWeb#list", "read"),
            Map.entry("CostCategoryControllerWeb#get", "read"),
            Map.entry("CostCategoryControllerWeb#create", "create"),
            Map.entry("CostCategoryControllerWeb#update", "edit"),
            Map.entry("CostCategoryControllerWeb#deactivate", "edit"),
            Map.entry("CostCategoryControllerWeb#seedDefaults", "configure"),
            Map.entry("BudgetAllocationControllerWeb#list", "read"),
            Map.entry("BudgetAllocationControllerWeb#upsert", "edit"),
            Map.entry("BudgetAllocationControllerWeb#delete", "delete"),
            Map.entry("ProjectCostControlControllerWeb#get", "read"),
            Map.entry("CompanyBankAccountControllerWeb#list", "read"),
            Map.entry("CompanyBankAccountControllerWeb#get", "read"),
            Map.entry("CompanyBankAccountControllerWeb#create", "create"),
            Map.entry("CompanyBankAccountControllerWeb#deactivate", "edit"));

    @Test
    void everyHandlerNeedsTheFinanceActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(BudgetAndBankGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('finance', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(CostCategoryControllerWeb.class, BudgetAllocationControllerWeb.class,
                        ProjectCostControlControllerWeb.class, CompanyBankAccountControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
