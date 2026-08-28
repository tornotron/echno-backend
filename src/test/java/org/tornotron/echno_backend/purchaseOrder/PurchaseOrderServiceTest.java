package org.tornotron.echno_backend.purchaseOrder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderCreationDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrder.mapper.PurchaseOrderMapper;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PurchaseOrderService. The repositories, mapper, and tenant helper are
 * mocked; the entity graph is built in memory. Focus is on the logic the service owns:
 * duplicate rejection, validation of referenced entities, the line-item price and
 * order-total arithmetic, and marking indent items as converted. The mapper is a mock,
 * so assertions are made on the PurchaseOrder captured on save() rather than the DTO.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    private static final Long ORG = 100L;

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

    private PurchaseOrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new PurchaseOrderService(purchaseOrderRepository, vendorRepository, indentRepository,
                purchaseOrderMapper, tenantEntityHelper, employeeRepository, projectRepository,
                purchaseOrderItemRepository, materialRepository, indentItemRepository,
                documentNumberAllocator, retryTemplate);
        // The template's own behaviour is covered by its own tests; here it just runs the work.
        lenient().when(retryTemplate.execute(anyString(), any(Predicate.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubMasterLookups() {
        Vendor vendor = new Vendor();
        vendor.setId(5L);
        Employee employee = new Employee();
        employee.setId(7L);
        Project project = new Project();
        project.setId(9L);
        Organization org = new Organization();
        org.setId(ORG);
        lenient().when(vendorRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.of(vendor));
        lenient().when(employeeRepository.findByIdAndOrganizationId(7L, ORG)).thenReturn(Optional.of(employee));
        lenient().when(projectRepository.findByIdAndOrganization_Id(9L, ORG)).thenReturn(Optional.of(project));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(org);
        lenient().when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentNumberAllocator.allocate(DocumentNumberType.PURCHASE_ORDER, ORG))
                .thenReturn("PO-2026-000042");
    }

    private PurchaseOrderCreationDto baseDto() {
        PurchaseOrderCreationDto dto = new PurchaseOrderCreationDto();
        dto.setVendorId(5L);
        dto.setCreatedBy(7L);
        dto.setProjectId(9L);
        dto.setStatus(PurchaseOrderStatus.DRAFT);
        return dto;
    }

    private PurchaseOrderItemCreationDto item(Long materialId, int qty, String unitPrice) {
        PurchaseOrderItemCreationDto i = new PurchaseOrderItemCreationDto();
        i.setMaterialId(materialId);
        i.setOrderedQuantity(qty);
        i.setUnitPrice(new BigDecimal(unitPrice));
        return i;
    }

    private Material material(Long id) {
        Material m = new Material();
        m.setId(id);
        return m;
    }

    @Test
    void createPurchaseOrder_takesItsNumberFromTheServerNotTheCaller() {
        stubMasterLookups();

        service.createPurchaseOrder(baseDto());

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getPoNumber()).isEqualTo("PO-2026-000042");
        verify(documentNumberAllocator).allocate(DocumentNumberType.PURCHASE_ORDER, ORG);
    }

    @Test
    void createPurchaseOrder_asApproved_isRefused() {
        PurchaseOrderCreationDto dto = baseDto();
        dto.setStatus(PurchaseOrderStatus.APPROVED);

        assertThatThrownBy(() -> service.createPurchaseOrder(dto))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("APPROVED");

        verify(purchaseOrderRepository, never()).save(any());
        verify(documentNumberAllocator, never()).allocate(any(), any());
    }

    @Test
    void createPurchaseOrder_asAnyOtherLaterState_isRefused() {
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            if (status == PurchaseOrderStatus.DRAFT) {
                continue;
            }
            PurchaseOrderCreationDto dto = baseDto();
            dto.setStatus(status);
            assertThatThrownBy(() -> service.createPurchaseOrder(dto))
                    .as("creating a purchase order as %s", status)
                    .isInstanceOf(InvalidRequestException.class);
        }
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void createPurchaseOrder_withNoStatus_startsAsDraft() {
        stubMasterLookups();
        PurchaseOrderCreationDto dto = baseDto();
        dto.setStatus(null);

        service.createPurchaseOrder(dto);

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
    }

    @Test
    void createPurchaseOrder_unknownVendor_throwsNotFound() {
        when(vendorRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPurchaseOrder(baseDto()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createPurchaseOrder_withoutItems_totalIsZero() {
        stubMasterLookups();

        service.createPurchaseOrder(baseDto());

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        PurchaseOrder saved = captor.getValue();
        assertThat(saved.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(saved.getItems()).isEmpty();
    }

    @Test
    void createPurchaseOrder_withItems_computesLineAndOrderTotals() {
        stubMasterLookups();
        when(materialRepository.findByIdAndOrganization_Id(11L, ORG)).thenReturn(Optional.of(material(11L)));
        when(materialRepository.findByIdAndOrganization_Id(12L, ORG)).thenReturn(Optional.of(material(12L)));

        PurchaseOrderCreationDto dto = baseDto();
        // 10 x 25.50 = 255.00 ; 3 x 100.00 = 300.00 ; order total = 555.00
        dto.setItems(List.of(item(11L, 10, "25.50"), item(12L, 3, "100.00")));

        service.createPurchaseOrder(dto);

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        PurchaseOrder saved = captor.getValue();

        assertThat(saved.getItems()).hasSize(2);
        assertThat(saved.getItems().get(0).getTotalPrice()).isEqualByComparingTo("255.00");
        assertThat(saved.getItems().get(1).getTotalPrice()).isEqualByComparingTo("300.00");
        assertThat(saved.getItems().get(0).getReceivedQuantity()).isZero();
        // every item is back-linked to its parent PO
        assertThat(saved.getItems()).allSatisfy(i -> assertThat(i.getPurchaseOrder()).isSameAs(saved));
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("555.00");
    }

    @Test
    void createPurchaseOrder_itemFromIndentItem_marksItConverted() {
        stubMasterLookups();
        when(materialRepository.findByIdAndOrganization_Id(11L, ORG)).thenReturn(Optional.of(material(11L)));
        IndentItem indentItem = new IndentItem();
        indentItem.setId(21L);
        indentItem.setConvertedToPurchaseOrder(false);
        when(indentItemRepository.findByIdAndOrganization_Id(21L, ORG)).thenReturn(Optional.of(indentItem));

        PurchaseOrderCreationDto dto = baseDto();
        PurchaseOrderItemCreationDto line = item(11L, 2, "50.00");
        line.setIndentItemId(21L);
        dto.setItems(List.of(line));

        service.createPurchaseOrder(dto);

        assertThat(indentItem.getConvertedToPurchaseOrder()).isTrue();
        verify(indentItemRepository).save(indentItem);
    }

    @Test
    void recalculateTotalAmount_nullSum_resetsToZero() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(1L);
        po.setTotalAmount(new BigDecimal("999.00"));
        when(purchaseOrderRepository.findByIdAndOrganization_Id(1L, ORG)).thenReturn(Optional.of(po));
        when(purchaseOrderItemRepository.sumTotalPriceByPurchaseOrderId(1L)).thenReturn(null);

        service.recalculateTotalAmount(1L);

        assertThat(po.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(purchaseOrderRepository).save(po);
    }

    @Test
    void recalculateTotalAmount_usesRepositorySum() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(1L);
        when(purchaseOrderRepository.findByIdAndOrganization_Id(1L, ORG)).thenReturn(Optional.of(po));
        when(purchaseOrderItemRepository.sumTotalPriceByPurchaseOrderId(1L)).thenReturn(new BigDecimal("742.00"));

        service.recalculateTotalAmount(1L);

        assertThat(po.getTotalAmount()).isEqualByComparingTo("742.00");
    }

    @Test
    void updatePurchaseOrderStatus_setsAndSaves() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(1L);
        po.setStatus(PurchaseOrderStatus.DRAFT);
        when(purchaseOrderRepository.findByIdAndOrganization_Id(1L, ORG)).thenReturn(Optional.of(po));

        service.updatePurchaseOrderStatus(1L, PurchaseOrderStatus.APPROVED);

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
        verify(purchaseOrderRepository).save(po);
    }
}
