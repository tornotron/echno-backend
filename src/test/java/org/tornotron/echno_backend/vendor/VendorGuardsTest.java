package org.tornotron.echno_backend.vendor;

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

/**
 * Every vendor handler, on the tenant API and its {@code /web} twin, asks the access matrix for the
 * vendors action it performs, and nothing else: reads of a vendor and its contacts need read, reads of
 * its bank accounts, tax identifiers, payment terms and spend summary need read-financials, and each
 * write needs the create, edit or delete it makes. A new handler fails here until it is given its row.
 */
class VendorGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("VendorController#createVendor", "create"),
            Map.entry("VendorController#getVendorById", "read"),
            Map.entry("VendorController#getAllVendors", "read"),
            Map.entry("VendorController#getAllVendorsPaginated", "read"),
            Map.entry("VendorController#searchVendors", "read"),
            Map.entry("VendorController#updateVendor", "edit"),
            Map.entry("VendorController#deleteVendor", "delete"),
            Map.entry("VendorController#getVendorSummary", "read-financials"),
            Map.entry("VendorController#getContacts", "read"),
            Map.entry("VendorController#addContact", "create"),
            Map.entry("VendorController#updateContact", "edit"),
            Map.entry("VendorController#deleteContact", "delete"),
            Map.entry("VendorController#getTaxIdentifiers", "read-financials"),
            Map.entry("VendorController#addTaxIdentifier", "create"),
            Map.entry("VendorController#updateTaxIdentifier", "edit"),
            Map.entry("VendorController#deleteTaxIdentifier", "delete"),
            Map.entry("VendorController#getBankAccounts", "read-financials"),
            Map.entry("VendorController#addBankAccount", "create"),
            Map.entry("VendorController#updateBankAccount", "edit"),
            Map.entry("VendorController#deleteBankAccount", "delete"),
            Map.entry("VendorController#getPaymentTerms", "read-financials"),
            Map.entry("VendorController#setPaymentTerms", "edit"),
            Map.entry("VendorController#deletePaymentTerms", "delete"),
            Map.entry("VendorControllerWeb#createVendor", "create"),
            Map.entry("VendorControllerWeb#getVendorById", "read"),
            Map.entry("VendorControllerWeb#getAllVendors", "read"),
            Map.entry("VendorControllerWeb#getAllVendorsPaginated", "read"),
            Map.entry("VendorControllerWeb#searchVendors", "read"),
            Map.entry("VendorControllerWeb#updateVendor", "edit"),
            Map.entry("VendorControllerWeb#deleteVendor", "delete"),
            Map.entry("VendorControllerWeb#getVendorSummary", "read-financials"),
            Map.entry("VendorControllerWeb#getContacts", "read"),
            Map.entry("VendorControllerWeb#addContact", "create"),
            Map.entry("VendorControllerWeb#updateContact", "edit"),
            Map.entry("VendorControllerWeb#deleteContact", "delete"),
            Map.entry("VendorControllerWeb#getTaxIdentifiers", "read-financials"),
            Map.entry("VendorControllerWeb#addTaxIdentifier", "create"),
            Map.entry("VendorControllerWeb#updateTaxIdentifier", "edit"),
            Map.entry("VendorControllerWeb#deleteTaxIdentifier", "delete"),
            Map.entry("VendorControllerWeb#getBankAccounts", "read-financials"),
            Map.entry("VendorControllerWeb#addBankAccount", "create"),
            Map.entry("VendorControllerWeb#updateBankAccount", "edit"),
            Map.entry("VendorControllerWeb#deleteBankAccount", "delete"),
            Map.entry("VendorControllerWeb#getPaymentTerms", "read-financials"),
            Map.entry("VendorControllerWeb#setPaymentTerms", "edit"),
            Map.entry("VendorControllerWeb#deletePaymentTerms", "delete"));

    @Test
    void everyHandlerNeedsTheVendorsActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(VendorGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('vendors', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(VendorController.class, VendorControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
