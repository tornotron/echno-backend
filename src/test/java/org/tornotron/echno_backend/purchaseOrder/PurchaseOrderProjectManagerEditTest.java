package org.tornotron.echno_backend.purchaseOrder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderUpdateDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrder.mapper.PurchaseOrderMapper;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemService;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemUpdateDto;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A project manager may raise and edit a purchase order, and still may not approve one (#855).
 *
 * <p>The controller guards open the header update and the line endpoints to
 * {@code project-manager}. Two routes then lead around the approval, and these tests pin that both
 * are closed by {@link PurchaseOrderEditPolicy} at the service: the header payload carries a status
 * field, so editing an order could approve it; and the lines of an approved order could be
 * rewritten after the approver signed off on them. A system admin keeps both.
 *
 * <p>The policy is real; only its {@link PurchaseOrderAccess} is mocked, answering whether the caller may
 * approve the order, so each test is a caller who holds {@code purchase-orders:approve} (the system
 * admin's) or does not.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderProjectManagerEditTest {

    private static final Long ORG_ID = 1L;
    private static final Long PO_ID = 204L;
    private static final Long ITEM_ID = 512L;

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
    @Mock private PurchaseOrderService purchaseOrderServiceForItems;
    @Mock private PurchaseOrderAccess approverAccess;

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private PurchaseOrderEditPolicy policy() {
        return new PurchaseOrderEditPolicy(approverAccess);
    }

    private PurchaseOrderService orderService() {
        return new PurchaseOrderService(purchaseOrderRepository, vendorRepository, indentRepository,
                purchaseOrderMapper, tenantEntityHelper, employeeRepository, projectRepository,
                purchaseOrderItemRepository, materialRepository, indentItemRepository,
                documentNumberAllocator, retryTemplate, statusTransitionRecorder,
                statusTransitionRepository, statusTransitionMapper, userContextService, policy(),
                PurchaseOrderAccessStubs.allowingEverything());
    }

    private PurchaseOrderItemService itemService() {
        return new PurchaseOrderItemService(purchaseOrderItemRepository, purchaseOrderRepository,
                materialRepository, indentItemRepository, tenantEntityHelper,
                purchaseOrderServiceForItems, policy(), PurchaseOrderAccessStubs.allowingEverything());
    }

    /**
     * Lenient because a draft edit that changes no status never asks: the policy lets it through
     * before it gets to the role, which is the point of those tests.
     */
    private void asProjectManager() {
        lenient().when(approverAccess.allows(eq("approve"), any())).thenReturn(false);
    }

    private void asSystemAdmin() {
        when(approverAccess.allows(eq("approve"), any())).thenReturn(true);
    }

    private PurchaseOrder order(PurchaseOrderStatus status) {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setPoNumber("PO-0204");
        order.setOrganization(organization);
        order.setStatus(status);
        return order;
    }

    private PurchaseOrder stored(PurchaseOrderStatus status) {
        PurchaseOrder order = order(status);
        when(purchaseOrderRepository.findByIdAndOrganization_Id(PO_ID, ORG_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private PurchaseOrderUpdateDto remarks(String remarks) {
        PurchaseOrderUpdateDto update = new PurchaseOrderUpdateDto();
        update.setId(PO_ID);
        update.setRemarks(remarks);
        return update;
    }

    // ---- what a project manager gains ----

    @Test
    void aProjectManagerEditsADraftOrder() {
        asProjectManager();
        PurchaseOrder order = stored(PurchaseOrderStatus.DRAFT);
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        orderService().updatePurchaseOrder(remarks("Vendor confirmed dispatch for 12 Feb"));

        assertThat(order.getRemarks()).isEqualTo("Vendor confirmed dispatch for 12 Feb");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
    }

    /** The web header card always sends the status it shows; the status an order already has is not a change. */
    @Test
    void aProjectManagerMaySendTheStatusTheOrderAlreadyHas() {
        asProjectManager();
        PurchaseOrder order = stored(PurchaseOrderStatus.DRAFT);
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PurchaseOrderUpdateDto update = remarks("Split delivery agreed");
        update.setStatus("DRAFT");

        orderService().updatePurchaseOrder(update);

        assertThat(order.getRemarks()).isEqualTo("Split delivery agreed");
    }

    // ---- what a project manager does not gain ----

    @Test
    void aProjectManagerCannotApproveAnOrderThroughTheHeaderUpdate() {
        asProjectManager();
        PurchaseOrder order = stored(PurchaseOrderStatus.DRAFT);
        PurchaseOrderUpdateDto update = remarks("Approved on site");
        update.setStatus("APPROVED");

        assertThatThrownBy(() -> orderService().updatePurchaseOrder(update))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("system-admin");

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void aProjectManagerCannotEditAnApprovedOrder() {
        asProjectManager();
        PurchaseOrder order = stored(PurchaseOrderStatus.APPROVED);

        assertThatThrownBy(() -> orderService().updatePurchaseOrder(remarks("Changed after approval")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("APPROVED");

        assertThat(order.getRemarks()).isNull();
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void aProjectManagerCannotAddALineToAnApprovedOrder() {
        asProjectManager();
        stored(PurchaseOrderStatus.APPROVED);
        PurchaseOrderItemCreationDto line = new PurchaseOrderItemCreationDto();
        line.setPurchaseOrderId(PO_ID);
        line.setMaterialId(11L);
        line.setOrderedQuantity(20);
        line.setUnitPrice(new BigDecimal("64.00"));

        assertThatThrownBy(() -> itemService().createPurchaseOrderItem(line))
                .isInstanceOf(AccessDeniedException.class);

        verify(purchaseOrderItemRepository, never()).save(any());
    }

    @Test
    void aProjectManagerCannotChangeALineOnAnApprovedOrder() {
        asProjectManager();
        Material material = new Material();
        material.setId(11L);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(ITEM_ID);
        item.setPurchaseOrder(order(PurchaseOrderStatus.SENT_TO_VENDOR));
        item.setMaterial(material);
        item.setOrderedQuantity(50);
        when(purchaseOrderItemRepository.findByIdAndOrganization_Id(ITEM_ID, ORG_ID)).thenReturn(Optional.of(item));
        PurchaseOrderItemUpdateDto update = new PurchaseOrderItemUpdateDto();
        update.setId(ITEM_ID);
        update.setOrderedQuantity(80);

        assertThatThrownBy(() -> itemService().updatePurchaseOrderItem(update))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(item.getOrderedQuantity()).isEqualTo(50);
        verify(purchaseOrderItemRepository, never()).save(any());
    }

    // ---- and a system admin keeps what it had ----

    @Test
    void aSystemAdminStillMovesTheStatusThroughTheHeaderUpdate() {
        asSystemAdmin();
        PurchaseOrder order = stored(PurchaseOrderStatus.DRAFT);
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PurchaseOrderUpdateDto update = remarks("Approved");
        update.setStatus("APPROVED");

        orderService().updatePurchaseOrder(update);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
    }

    @Test
    void aSystemAdminStillEditsAnApprovedOrder() {
        asSystemAdmin();
        PurchaseOrder order = stored(PurchaseOrderStatus.APPROVED);
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        orderService().updatePurchaseOrder(remarks("Delivery moved to the north gate"));

        assertThat(order.getRemarks()).isEqualTo("Delivery moved to the north gate");
    }
}
