package org.tornotron.echno_backend.purchaseOrder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrder.mapper.PurchaseOrderMapper;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that a status change somebody made is written to the shared trail against that person.
 *
 * <p>The derived moves the reconciler makes are covered in
 * {@code PurchaseOrderReceiptReconcilerTest}. Both halves are needed: a trail holding only the
 * derived moves would read as though the system made every change an order ever went through.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderStatusTrailTest {

    private static final Long ORG = 100L;
    private static final Long PO_ID = 204L;

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private IndentRepository indentRepository;
    @Mock private PurchaseOrderMapper purchaseOrderMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private IndentItemRepository indentItemRepository;
    @Mock private DocumentNumberAllocator documentNumberAllocator;
    @Mock private TransactionRetryTemplate retryTemplate;
    @Mock private StatusTransitionRecorder statusTransitionRecorder;
    @Mock private StatusTransitionRepository statusTransitionRepository;
    @Mock private StatusTransitionMapper statusTransitionMapper;
    @Mock private UserContextService userContextService;

    private PurchaseOrderService service;
    private Organization organization;
    private User actor;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        actor = new User();
        actor.setId(77L);
        service = new PurchaseOrderService(purchaseOrderRepository, vendorRepository, indentRepository,
                purchaseOrderMapper, tenantEntityHelper, employeeRepository, projectRepository,
                purchaseOrderItemRepository, materialRepository, indentItemRepository,
                documentNumberAllocator, retryTemplate, statusTransitionRecorder,
                statusTransitionRepository, statusTransitionMapper, userContextService);
        lenient().when(userContextService.getCurrentUser()).thenReturn(actor);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void recordsAStatusChangeSomebodyMadeAgainstThatPerson() {
        PurchaseOrder order = order(PurchaseOrderStatus.DRAFT);
        when(purchaseOrderRepository.findByIdAndOrganization_Id(PO_ID, ORG)).thenReturn(Optional.of(order));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updatePurchaseOrderStatus(PO_ID, PurchaseOrderStatus.APPROVED);

        verify(statusTransitionRecorder).recordChange(
                eq("PURCHASE_ORDER"), eq(PO_ID), eq(organization),
                eq("DRAFT"), eq("APPROVED"), eq(actor), isNull());
    }

    @Test
    void refusesToReadTheTrailOfAnOrderThatIsNotInThisOrganization() {
        when(purchaseOrderRepository.findByIdAndOrganization_Id(PO_ID, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatusHistory(PO_ID, 0, 20))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private PurchaseOrder order(PurchaseOrderStatus status) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setPoNumber("PO-2026-000001");
        order.setStatus(status);
        order.setOrganization(organization);
        return order;
    }
}
