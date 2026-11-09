package org.tornotron.echno_backend.modules.workprogress;

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
import org.tornotron.echno_backend.modules.workprogress.billing.payment.web.ContractBillPaymentController;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.web.ContractBillPaymentControllerWeb;
import org.tornotron.echno_backend.modules.workprogress.billing.web.ContractBillingController;
import org.tornotron.echno_backend.modules.workprogress.billing.web.ContractBillingControllerWeb;
import org.tornotron.echno_backend.modules.workprogress.web.ProgressInspectionController;
import org.tornotron.echno_backend.modules.workprogress.web.ProgressInspectionControllerWeb;

/**
 * Every work progress handler, on the tenant API and its {@code /web} twin, asks the access matrix
 * for the one action it performs, and nothing else. Progress inspections are the progress area:
 * reads need read and recording one or its evidence needs create. The billing chain and its payments
 * are progress-bills: the contract's billing set-up (BOQ, deduction rules) and naming a project's
 * payment authorizer need edit, and each step of a bill needs its own named action, with raising a
 * payment under approve. The steps after raising a payment need only read, because the project's
 * named payment authorizer, checked in {@code PaymentAuthorizers}, is what decides them. A new
 * handler fails here until it is given its row.
 */
class WorkProgressGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by area and action. */
    static final Map<String, String> PERMISSION_BY_HANDLER = Map.ofEntries(
            Map.entry("ProgressInspectionController#list", "progress:read"),
            Map.entry("ProgressInspectionController#get", "progress:read"),
            Map.entry("ProgressInspectionController#record", "progress:create"),
            Map.entry("ProgressInspectionController#listEvidence", "progress:read"),
            Map.entry("ProgressInspectionController#presignEvidence", "progress:create"),
            Map.entry("ProgressInspectionController#registerEvidence", "progress:create"),
            Map.entry("ProgressInspectionControllerWeb#list", "progress:read"),
            Map.entry("ProgressInspectionControllerWeb#get", "progress:read"),
            Map.entry("ProgressInspectionControllerWeb#record", "progress:create"),
            Map.entry("ProgressInspectionControllerWeb#listEvidence", "progress:read"),
            Map.entry("ProgressInspectionControllerWeb#presignEvidence", "progress:create"),
            Map.entry("ProgressInspectionControllerWeb#registerEvidence", "progress:create"),
            Map.entry("ContractBillingController#overview", "progress-bills:read"),
            Map.entry("ContractBillingController#projects", "progress-bills:read"),
            Map.entry("ContractBillingController#project", "progress-bills:read"),
            Map.entry("ContractBillingController#activity", "progress-bills:read"),
            Map.entry("ContractBillingController#contracts", "progress-bills:read"),
            Map.entry("ContractBillingController#contract", "progress-bills:read"),
            Map.entry("ContractBillingController#listBoq", "progress-bills:read"),
            Map.entry("ContractBillingController#addBoqItem", "progress-bills:edit"),
            Map.entry("ContractBillingController#updateBoqItem", "progress-bills:edit"),
            Map.entry("ContractBillingController#deleteBoqItem", "progress-bills:edit"),
            Map.entry("ContractBillingController#listRules", "progress-bills:read"),
            Map.entry("ContractBillingController#addRule", "progress-bills:edit"),
            Map.entry("ContractBillingController#updateRule", "progress-bills:edit"),
            Map.entry("ContractBillingController#deleteRule", "progress-bills:edit"),
            Map.entry("ContractBillingController#listRequirements", "progress-bills:read"),
            Map.entry("ContractBillingController#addRequirement", "progress-bills:verify"),
            Map.entry("ContractBillingController#updateRequirement", "progress-bills:verify"),
            Map.entry("ContractBillingController#deleteRequirement", "progress-bills:verify"),
            Map.entry("ContractBillingController#listBills", "progress-bills:read"),
            Map.entry("ContractBillingController#createBill", "progress-bills:prepare"),
            Map.entry("ContractBillingController#getBill", "progress-bills:read"),
            Map.entry("ContractBillingController#updateBill", "progress-bills:prepare"),
            Map.entry("ContractBillingController#submitBill", "progress-bills:prepare"),
            Map.entry("ContractBillingController#cancelBill", "progress-bills:prepare"),
            Map.entry("ContractBillingController#saveMeasurement", "progress-bills:verify"),
            Map.entry("ContractBillingController#verifyBill", "progress-bills:verify"),
            Map.entry("ContractBillingController#returnBill", "progress-bills:verify"),
            Map.entry("ContractBillingController#replaceAdjustments", "progress-bills:certify"),
            Map.entry("ContractBillingController#certifyBill", "progress-bills:certify"),
            Map.entry("ContractBillingController#approveBill", "progress-bills:approve"),
            Map.entry("ContractBillingController#billEvents", "progress-bills:read"),
            Map.entry("ContractBillingController#addNote", "progress-bills:prepare"),
            Map.entry("ContractBillingController#billPdf", "progress-bills:read"),
            Map.entry("ContractBillingController#listDocuments", "progress-bills:read"),
            Map.entry("ContractBillingController#presignDocuments", "progress-bills:prepare"),
            Map.entry("ContractBillingController#registerDocuments", "progress-bills:prepare"),
            Map.entry("ContractBillingController#deleteDocument", "progress-bills:prepare"),
            Map.entry("ContractBillingControllerWeb#overview", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#projects", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#project", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#activity", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#contracts", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#contract", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#listBoq", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#addBoqItem", "progress-bills:edit"),
            Map.entry("ContractBillingControllerWeb#updateBoqItem", "progress-bills:edit"),
            Map.entry("ContractBillingControllerWeb#deleteBoqItem", "progress-bills:edit"),
            Map.entry("ContractBillingControllerWeb#listRules", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#addRule", "progress-bills:edit"),
            Map.entry("ContractBillingControllerWeb#updateRule", "progress-bills:edit"),
            Map.entry("ContractBillingControllerWeb#deleteRule", "progress-bills:edit"),
            Map.entry("ContractBillingControllerWeb#listRequirements", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#addRequirement", "progress-bills:verify"),
            Map.entry("ContractBillingControllerWeb#updateRequirement", "progress-bills:verify"),
            Map.entry("ContractBillingControllerWeb#deleteRequirement", "progress-bills:verify"),
            Map.entry("ContractBillingControllerWeb#listBills", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#createBill", "progress-bills:prepare"),
            Map.entry("ContractBillingControllerWeb#getBill", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#updateBill", "progress-bills:prepare"),
            Map.entry("ContractBillingControllerWeb#submitBill", "progress-bills:prepare"),
            Map.entry("ContractBillingControllerWeb#cancelBill", "progress-bills:prepare"),
            Map.entry("ContractBillingControllerWeb#saveMeasurement", "progress-bills:verify"),
            Map.entry("ContractBillingControllerWeb#verifyBill", "progress-bills:verify"),
            Map.entry("ContractBillingControllerWeb#returnBill", "progress-bills:verify"),
            Map.entry("ContractBillingControllerWeb#replaceAdjustments", "progress-bills:certify"),
            Map.entry("ContractBillingControllerWeb#certifyBill", "progress-bills:certify"),
            Map.entry("ContractBillingControllerWeb#approveBill", "progress-bills:approve"),
            Map.entry("ContractBillingControllerWeb#billEvents", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#addNote", "progress-bills:prepare"),
            Map.entry("ContractBillingControllerWeb#billPdf", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#listDocuments", "progress-bills:read"),
            Map.entry("ContractBillingControllerWeb#presignDocuments", "progress-bills:prepare"),
            Map.entry("ContractBillingControllerWeb#registerDocuments", "progress-bills:prepare"),
            Map.entry("ContractBillingControllerWeb#deleteDocument", "progress-bills:prepare"),
            Map.entry("ContractBillPaymentController#list", "progress-bills:read"),
            Map.entry("ContractBillPaymentController#authorizer", "progress-bills:read"),
            Map.entry("ContractBillPaymentController#setAuthorizer", "progress-bills:edit"),
            Map.entry("ContractBillPaymentController#clearAuthorizer", "progress-bills:edit"),
            Map.entry("ContractBillPaymentController#raise", "progress-bills:approve"),
            Map.entry("ContractBillPaymentController#authorize", "progress-bills:read"),
            Map.entry("ContractBillPaymentController#reject", "progress-bills:read"),
            Map.entry("ContractBillPaymentController#record", "progress-bills:read"),
            Map.entry("ContractBillPaymentController#sync", "progress-bills:read"),
            Map.entry("ContractBillPaymentControllerWeb#list", "progress-bills:read"),
            Map.entry("ContractBillPaymentControllerWeb#authorizer", "progress-bills:read"),
            Map.entry("ContractBillPaymentControllerWeb#setAuthorizer", "progress-bills:edit"),
            Map.entry("ContractBillPaymentControllerWeb#clearAuthorizer", "progress-bills:edit"),
            Map.entry("ContractBillPaymentControllerWeb#raise", "progress-bills:approve"),
            Map.entry("ContractBillPaymentControllerWeb#authorize", "progress-bills:read"),
            Map.entry("ContractBillPaymentControllerWeb#reject", "progress-bills:read"),
            Map.entry("ContractBillPaymentControllerWeb#record", "progress-bills:read"),
            Map.entry("ContractBillPaymentControllerWeb#sync", "progress-bills:read"));


    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(WorkProgressGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(PERMISSION_BY_HANDLER.keySet());
        PERMISSION_BY_HANDLER.forEach((handler, permission) -> {
            String[] parts = permission.split(":");
            assertThat(guards.get(handler)).as(handler)
                    .isEqualTo("@access.can('" + parts[0] + "', '" + parts[1] + "')");
        });
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(ProgressInspectionController.class, ProgressInspectionControllerWeb.class,
                        ContractBillingController.class, ContractBillingControllerWeb.class,
                        ContractBillPaymentController.class, ContractBillPaymentControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
