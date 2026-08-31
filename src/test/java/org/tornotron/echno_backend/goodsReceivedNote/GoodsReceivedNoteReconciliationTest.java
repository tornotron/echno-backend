package org.tornotron.echno_backend.goodsReceivedNote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteCreationDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GrnItemDto;
import org.tornotron.echno_backend.goodsReceivedNote.mapper.GoodsReceivedNoteMapper;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.grnItem.GrnItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderReceiptReconciler;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests how a goods receipt is wired to the purchase order it cites: that the reconciliation
 * runs, that it runs before the stock event so a refused delivery raises no stock, and that a
 * receipt let through as an over-receipt says so on the document.
 *
 * <p>The reconciler's own arithmetic is covered in {@code PurchaseOrderReceiptReconcilerTest};
 * here it is a mock, and what is asserted is the order of events around it.
 */
@ExtendWith(MockitoExtension.class)
class GoodsReceivedNoteReconciliationTest {

    private static final Long ORG = 100L;

    @Mock private GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    @Mock private GrnItemRepository grnItemRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private UserRepository userRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private GoodsReceivedNoteMapper goodsReceivedNoteMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private DocumentNumberAllocator documentNumberAllocator;
    @Mock private TransactionRetryTemplate retryTemplate;
    @Mock private PurchaseOrderReceiptReconciler purchaseOrderReceiptReconciler;

    private GoodsReceivedNoteService service;
    private PurchaseOrder purchaseOrder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new GoodsReceivedNoteService(goodsReceivedNoteRepository, grnItemRepository,
                vendorRepository, userRepository, materialRepository, eventPublisher,
                goodsReceivedNoteMapper, tenantEntityHelper, employeeRepository, projectRepository,
                storageLocationRepository, purchaseOrderRepository,
                documentNumberAllocator, retryTemplate, purchaseOrderReceiptReconciler);
        lenient().when(retryTemplate.execute(anyString(), any(Predicate.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());

        Vendor vendor = new Vendor();
        vendor.setId(5L);
        Employee employee = new Employee();
        employee.setId(7L);
        Project project = new Project();
        project.setId(9L);
        purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(3L);
        purchaseOrder.setPoNumber("PO-2026-000001");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT_TO_VENDOR);
        Organization organization = new Organization();
        organization.setId(ORG);

        lenient().when(vendorRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.of(vendor));
        lenient().when(employeeRepository.findByIdAndOrganizationId(7L, ORG)).thenReturn(Optional.of(employee));
        lenient().when(projectRepository.findByIdAndOrganization_Id(9L, ORG)).thenReturn(Optional.of(project));
        lenient().when(purchaseOrderRepository.findByIdAndOrganization_Id(3L, ORG)).thenReturn(Optional.of(purchaseOrder));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        lenient().when(goodsReceivedNoteRepository.save(any(GoodsReceivedNote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(documentNumberAllocator.allocate(DocumentNumberType.GOODS_RECEIVED_NOTE, ORG))
                .thenReturn("GRN-2026-000042");
        lenient().when(materialRepository.findByIdAndOrganization_Id(eq(11L), eq(ORG)))
                .thenAnswer(invocation -> {
                    Material material = new Material();
                    material.setId(11L);
                    return Optional.of(material);
                });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void postsTheReceiptBackOntoTheOrderItCites() {
        stubOutcome(new PurchaseOrderReceiptReconciler.ReceiptOutcome(1, false, null));

        service.createGoodsReceivedNote(creationDto(9, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GrnItem>> lines = ArgumentCaptor.forClass(List.class);
        verify(purchaseOrderReceiptReconciler).applyReceipt(
                eq(purchaseOrder), any(GoodsReceivedNote.class), lines.capture(), eq(false));
        assertThat(lines.getValue()).hasSize(1);
        assertThat(lines.getValue().get(0).getReceivedQuantity()).isEqualTo(9);
    }

    @Test
    void passesTheAcknowledgementFromThePayloadThrough() {
        stubOutcome(new PurchaseOrderReceiptReconciler.ReceiptOutcome(1, true, null));

        service.createGoodsReceivedNote(creationDto(105, true));

        verify(purchaseOrderReceiptReconciler).applyReceipt(
                any(PurchaseOrder.class), any(GoodsReceivedNote.class), any(), eq(true));
    }

    @Test
    void raisesNoStockWhenTheOrderRefusesTheDelivery() {
        when(purchaseOrderReceiptReconciler.applyReceipt(any(), any(), any(), anyBoolean()))
                .thenThrow(new InvalidRequestException("would take it to 10000"));

        assertThatThrownBy(() -> service.createGoodsReceivedNote(creationDto(10_000, null)))
                .isInstanceOf(InvalidRequestException.class);

        verify(eventPublisher, never()).publishEvent(any(GrnCreatedEvent.class));
    }

    @Test
    void marksTheNoteWhenTheOverReceiptWasLetThrough() {
        stubOutcome(new PurchaseOrderReceiptReconciler.ReceiptOutcome(1, true, null));

        service.createGoodsReceivedNote(creationDto(105, true));

        ArgumentCaptor<GoodsReceivedNote> saved = ArgumentCaptor.forClass(GoodsReceivedNote.class);
        verify(goodsReceivedNoteRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().isOverReceiptAcknowledged()).isTrue();
    }

    @Test
    void leavesTheNoteUnmarkedWhenTheReceiptStaysWithinTheOrder() {
        stubOutcome(new PurchaseOrderReceiptReconciler.ReceiptOutcome(1, false, null));

        service.createGoodsReceivedNote(creationDto(9, null));

        ArgumentCaptor<GoodsReceivedNote> saved = ArgumentCaptor.forClass(GoodsReceivedNote.class);
        verify(goodsReceivedNoteRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().isOverReceiptAcknowledged()).isFalse();
    }

    private void stubOutcome(PurchaseOrderReceiptReconciler.ReceiptOutcome outcome) {
        when(purchaseOrderReceiptReconciler.applyReceipt(any(), any(), any(), anyBoolean()))
                .thenReturn(outcome);
    }

    private GoodsReceivedNoteCreationDto creationDto(int received, Boolean allowOverReceipt) {
        GrnItemDto item = new GrnItemDto();
        item.setMaterialId(11L);
        item.setOrderedQuantity(10);
        item.setReceivedQuantity(received);
        item.setUnitCost(new BigDecimal("25.50"));

        GoodsReceivedNoteCreationDto dto = new GoodsReceivedNoteCreationDto();
        dto.setReceivedOn(LocalDateTime.of(2026, 8, 3, 10, 0));
        dto.setReceivedByEmployeeId(7L);
        dto.setVendorId(5L);
        dto.setProjectId(9L);
        dto.setPurchaseOrderId(3L);
        dto.setItems(List.of(item));
        dto.setAllowOverReceipt(allowOverReceipt);
        return dto;
    }
}
