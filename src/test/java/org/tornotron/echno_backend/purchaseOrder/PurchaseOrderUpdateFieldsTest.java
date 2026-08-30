package org.tornotron.echno_backend.purchaseOrder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderUpdateDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrder.mapper.PurchaseOrderMapper;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemService;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemUpdateDto;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fields a purchase-order edit is allowed to move.
 *
 * <p>Both edit screens post through these two update calls, and until now neither of them could
 * reach the backend at all: echno-core addressed the id in the path, where no route exists.
 * Once the client is pointed at the right route the payload becomes visible for the first time,
 * and two of the controls on those screens turned out to have nothing behind them. The purchase
 * order's project select and the line's material select were both sent and both dropped, because
 * no such field existed on either update DTO.
 *
 * <p>Both are real columns with real screens, so they are accepted here rather than removed from
 * the client. The material carries the one restriction the data needs: once a goods received note
 * has posted against a line, that line is the record of what arrived.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderUpdateFieldsTest {

    private static final Long ORG_ID = 100L;
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
    @Mock private PurchaseOrderService purchaseOrderService;

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private PurchaseOrderService orderService() {
        return new PurchaseOrderService(
                purchaseOrderRepository,
                vendorRepository,
                indentRepository,
                purchaseOrderMapper,
                tenantEntityHelper,
                employeeRepository,
                projectRepository,
                purchaseOrderItemRepository,
                materialRepository,
                indentItemRepository,
                documentNumberAllocator,
                retryTemplate);
    }

    private PurchaseOrderItemService itemService() {
        return new PurchaseOrderItemService(
                purchaseOrderItemRepository,
                purchaseOrderRepository,
                materialRepository,
                indentItemRepository,
                tenantEntityHelper,
                purchaseOrderService);
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        return organization;
    }

    private Project project(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setOrganization(organization());
        return project;
    }

    private Material material(Long id) {
        Material material = new Material();
        material.setId(id);
        material.setOrganization(organization());
        return material;
    }

    private PurchaseOrder purchaseOrder() {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(PO_ID);
        purchaseOrder.setOrganization(organization());
        purchaseOrder.setStatus(PurchaseOrderStatus.DRAFT);
        purchaseOrder.setProject(project(1L));
        purchaseOrder.setTotalAmount(new BigDecimal("492500.00"));
        return purchaseOrder;
    }

    private PurchaseOrderItem lineItem(int receivedQuantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(ITEM_ID);
        item.setOrganization(organization());
        item.setPurchaseOrder(purchaseOrder());
        item.setMaterial(material(11L));
        item.setOrderedQuantity(50);
        item.setReceivedQuantity(receivedQuantity);
        item.setUnitPrice(new BigDecimal("64.00"));
        return item;
    }

    @Test
    void updatingAPurchaseOrder_movesItToTheProjectTheEditSent() {
        // The project select on the PO header card. The field had no home on the update DTO, so
        // the value arrived and went nowhere.
        PurchaseOrder stored = purchaseOrder();
        when(purchaseOrderRepository.findByIdAndOrganization_Id(PO_ID, ORG_ID)).thenReturn(Optional.of(stored));
        when(projectRepository.findByIdAndOrganization_Id(17L, ORG_ID)).thenReturn(Optional.of(project(17L)));
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrderUpdateDto update = new PurchaseOrderUpdateDto();
        update.setId(PO_ID);
        update.setProjectId(17L);

        orderService().updatePurchaseOrder(update);

        assertThat(stored.getProject().getId()).isEqualTo(17L);
    }

    @Test
    void updatingAPurchaseOrder_refusesAProjectFromAnotherOrganization() {
        // The lookup is tenant-scoped, so a project id from elsewhere is simply not found rather
        // than reallocating the order across a tenant boundary.
        when(purchaseOrderRepository.findByIdAndOrganization_Id(PO_ID, ORG_ID)).thenReturn(Optional.of(purchaseOrder()));
        when(projectRepository.findByIdAndOrganization_Id(999L, ORG_ID)).thenReturn(Optional.empty());

        PurchaseOrderUpdateDto update = new PurchaseOrderUpdateDto();
        update.setId(PO_ID);
        update.setProjectId(999L);

        assertThatThrownBy(() -> orderService().updatePurchaseOrder(update))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void updatingAPurchaseOrder_leavesTheTotalToTheLineItems() {
        // totalAmount is on the payload and always has been ignored: the total is the sum of the
        // lines and is recomputed whenever one changes. Pinned so honouring it is a decision
        // somebody makes rather than something that slides in with a rename.
        PurchaseOrder stored = purchaseOrder();
        when(purchaseOrderRepository.findByIdAndOrganization_Id(PO_ID, ORG_ID)).thenReturn(Optional.of(stored));
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrderUpdateDto update = new PurchaseOrderUpdateDto();
        update.setId(PO_ID);
        update.setTotalAmount(new BigDecimal("1.00"));

        orderService().updatePurchaseOrder(update);

        assertThat(stored.getTotalAmount()).isEqualByComparingTo("492500.00");
    }

    @Test
    void updatingALineItem_changesTheMaterialTheEditSent() {
        // The material select on the Edit Item row, sent on every save and dropped until now.
        PurchaseOrderItem stored = lineItem(0);
        when(purchaseOrderItemRepository.findByIdAndOrganization_Id(ITEM_ID, ORG_ID)).thenReturn(Optional.of(stored));
        when(materialRepository.findByIdAndOrganization_Id(88L, ORG_ID)).thenReturn(Optional.of(material(88L)));
        when(purchaseOrderItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrderItemUpdateDto update = new PurchaseOrderItemUpdateDto();
        update.setId(ITEM_ID);
        update.setMaterialId(88L);

        itemService().updatePurchaseOrderItem(update);

        assertThat(stored.getMaterial().getId()).isEqualTo(88L);
    }

    @Test
    void updatingALineItem_refusesToChangeTheMaterialOnceStockHasArrived() {
        // A goods received note has posted against this line. Swapping the material underneath it
        // would move a real receipt onto stock nobody delivered.
        when(purchaseOrderItemRepository.findByIdAndOrganization_Id(ITEM_ID, ORG_ID))
                .thenReturn(Optional.of(lineItem(20)));

        PurchaseOrderItemUpdateDto update = new PurchaseOrderItemUpdateDto();
        update.setId(ITEM_ID);
        update.setMaterialId(88L);

        assertThatThrownBy(() -> itemService().updatePurchaseOrderItem(update))
                .isInstanceOf(InvalidRequestException.class);

        verify(purchaseOrderItemRepository, never()).save(any());
    }

    @Test
    void updatingALineItem_leavesAReceivedLineEditableInEveryOtherWay() {
        // The restriction is on the material alone. Correcting the price of a part-delivered line
        // is ordinary work and must not be caught by it.
        PurchaseOrderItem stored = lineItem(20);
        when(purchaseOrderItemRepository.findByIdAndOrganization_Id(ITEM_ID, ORG_ID)).thenReturn(Optional.of(stored));
        when(purchaseOrderItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrderItemUpdateDto update = new PurchaseOrderItemUpdateDto();
        update.setId(ITEM_ID);
        update.setUnitPrice(new BigDecimal("70.00"));

        itemService().updatePurchaseOrderItem(update);

        assertThat(stored.getUnitPrice()).isEqualByComparingTo("70.00");
        // Line total follows the price, and the order total is recomputed from the lines.
        assertThat(stored.getTotalPrice()).isEqualByComparingTo("3500.00");
        verify(purchaseOrderService).recalculateTotalAmount(PO_ID);
    }

    @Test
    void updatingALineItem_acceptsTheMaterialItAlreadyHas_evenAfterAReceipt() {
        // The web client sends the material on every save, including saves that only change the
        // remarks. Rejecting an unchanged material would turn every edit of a part-delivered line
        // into a 400.
        PurchaseOrderItem stored = lineItem(20);
        when(purchaseOrderItemRepository.findByIdAndOrganization_Id(ITEM_ID, ORG_ID)).thenReturn(Optional.of(stored));
        when(purchaseOrderItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrderItemUpdateDto update = new PurchaseOrderItemUpdateDto();
        update.setId(ITEM_ID);
        update.setMaterialId(11L);
        update.setRemarks("Vendor revised delivery window");

        itemService().updatePurchaseOrderItem(update);

        assertThat(stored.getRemarks()).isEqualTo("Vendor revised delivery window");
    }
}
