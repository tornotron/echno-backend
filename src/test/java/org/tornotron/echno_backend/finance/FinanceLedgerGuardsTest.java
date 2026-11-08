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
import org.tornotron.echno_backend.finance.ledger.web.AccountControllerWeb;
import org.tornotron.echno_backend.finance.ledger.web.CustomerControllerWeb;
import org.tornotron.echno_backend.finance.ledger.web.JournalEntryControllerWeb;
import org.tornotron.echno_backend.finance.ledger.web.JournalExportControllerWeb;
import org.tornotron.echno_backend.finance.posting.web.PostingAccountMappingControllerWeb;
import org.tornotron.echno_backend.finance.report.web.ReportControllerWeb;
import org.tornotron.echno_backend.finance.settings.web.FinanceSettingsControllerWeb;

/**
 * Every ledger handler (accounts, journal entries and their export, customers, posting accounts,
 * finance settings and the reports) asks the access matrix for the finance action it performs, and
 * nothing else: reads need read, adding a record needs create, a change needs edit, reversing a journal
 * entry needs approve, and the organization-wide setup only the system admin changed needs configure.
 * A new handler fails here until it is given its row.
 */
class FinanceLedgerGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("AccountControllerWeb#list", "read"),
            Map.entry("AccountControllerWeb#tree", "read"),
            Map.entry("AccountControllerWeb#get", "read"),
            Map.entry("AccountControllerWeb#getByCode", "read"),
            Map.entry("AccountControllerWeb#export", "read"),
            Map.entry("AccountControllerWeb#create", "create"),
            Map.entry("AccountControllerWeb#update", "edit"),
            Map.entry("AccountControllerWeb#deactivate", "edit"),
            Map.entry("AccountControllerWeb#importCsv", "configure"),
            Map.entry("AccountControllerWeb#seedDefaults", "configure"),
            Map.entry("JournalEntryControllerWeb#get", "read"),
            Map.entry("JournalEntryControllerWeb#getAll", "read"),
            Map.entry("JournalEntryControllerWeb#post", "create"),
            Map.entry("JournalEntryControllerWeb#reverse", "approve"),
            Map.entry("JournalExportControllerWeb#export", "read"),
            Map.entry("CustomerControllerWeb#list", "read"),
            Map.entry("CustomerControllerWeb#get", "read"),
            Map.entry("CustomerControllerWeb#create", "create"),
            Map.entry("CustomerControllerWeb#update", "edit"),
            Map.entry("CustomerControllerWeb#deactivate", "edit"),
            Map.entry("PostingAccountMappingControllerWeb#list", "read"),
            Map.entry("PostingAccountMappingControllerWeb#upsert", "configure"),
            Map.entry("PostingAccountMappingControllerWeb#delete", "configure"),
            Map.entry("FinanceSettingsControllerWeb#get", "read"),
            Map.entry("FinanceSettingsControllerWeb#update", "configure"),
            Map.entry("ReportControllerWeb#trialBalance", "read"),
            Map.entry("ReportControllerWeb#profitAndLoss", "read"),
            Map.entry("ReportControllerWeb#balanceSheet", "read"));

    @Test
    void everyHandlerNeedsTheFinanceActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(FinanceLedgerGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('finance', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(AccountControllerWeb.class, JournalEntryControllerWeb.class,
                        JournalExportControllerWeb.class, CustomerControllerWeb.class,
                        PostingAccountMappingControllerWeb.class, FinanceSettingsControllerWeb.class,
                        ReportControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
