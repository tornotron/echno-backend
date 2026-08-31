package org.tornotron.echno_backend.goodsReceivedNote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
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
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderReceiptReconciler;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GoodsReceivedNoteService.createGoodsReceivedNote, the receipt path that
 * seeds stock valuation. Repositories, mapper, tenant helper, and the event publisher are
 * mocked; the entity graph is built in memory. Focus is on the logic the service owns:
 * duplicate-number rejection, validation of the referenced vendor/employee/project/PO,
 * building the GRN line items from their materials, and publishing GrnCreatedEvent (the
 * trigger for the downstream inventory update). Assertions read the entities captured on
 * save/publish since the mapper is a mock.
 */
@ExtendWith(MockitoExtension.class)
class GoodsReceivedNoteServiceTest {

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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new GoodsReceivedNoteService(goodsReceivedNoteRepository, grnItemRepository,
                vendorRepository, userRepository, materialRepository, eventPublisher,
                goodsReceivedNoteMapper, tenantEntityHelper, employeeRepository, projectRepository,
                storageLocationRepository, purchaseOrderRepository,
                documentNumberAllocator, retryTemplate, purchaseOrderReceiptReconciler);
        lenient().when(purchaseOrderReceiptReconciler.applyReceipt(any(), any(), any(), anyBoolean()))
                .thenReturn(new PurchaseOrderReceiptReconciler.ReceiptOutcome(0, false, null));
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
        PurchaseOrder po = new PurchaseOrder();
        po.setId(3L);
        Organization org = new Organization();
        org.setId(ORG);
        lenient().when(vendorRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.of(vendor));
        lenient().when(employeeRepository.findByIdAndOrganizationId(7L, ORG)).thenReturn(Optional.of(employee));
        lenient().when(projectRepository.findByIdAndOrganization_Id(9L, ORG)).thenReturn(Optional.of(project));
        lenient().when(purchaseOrderRepository.findByIdAndOrganization_Id(3L, ORG)).thenReturn(Optional.of(po));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(org);
        lenient().when(goodsReceivedNoteRepository.save(any(GoodsReceivedNote.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentNumberAllocator.allocate(DocumentNumberType.GOODS_RECEIVED_NOTE, ORG))
                .thenReturn("GRN-2026-000042");
    }

    private GrnItemDto itemDto(Long materialId, int ordered, int received, String unitCost) {
        GrnItemDto dto = new GrnItemDto();
        dto.setMaterialId(materialId);
        dto.setOrderedQuantity(ordered);
        dto.setReceivedQuantity(received);
        dto.setUnitCost(new BigDecimal(unitCost));
        return dto;
    }

    private GoodsReceivedNoteCreationDto baseDto() {
        GoodsReceivedNoteCreationDto dto = new GoodsReceivedNoteCreationDto();
        dto.setReceivedOn(LocalDateTime.of(2026, 8, 3, 10, 0));
        dto.setReceivedByEmployeeId(7L);
        dto.setVendorId(5L);
        dto.setProjectId(9L);
        dto.setPurchaseOrderId(3L);
        dto.setItems(List.of(itemDto(11L, 10, 9, "25.50")));
        return dto;
    }

    private Material material(Long id) {
        Material m = new Material();
        m.setId(id);
        return m;
    }

    @Test
    void create_takesItsNumberFromTheServerNotTheCaller() {
        stubMasterLookups();
        when(materialRepository.findByIdAndOrganization_Id(11L, ORG)).thenReturn(Optional.of(material(11L)));

        service.createGoodsReceivedNote(baseDto());

        ArgumentCaptor<GoodsReceivedNote> captor = ArgumentCaptor.forClass(GoodsReceivedNote.class);
        verify(goodsReceivedNoteRepository).save(captor.capture());
        assertThat(captor.getValue().getGrnNumber()).isEqualTo("GRN-2026-000042");
        verify(documentNumberAllocator).allocate(DocumentNumberType.GOODS_RECEIVED_NOTE, ORG);
    }

    @Test
    void create_unknownPurchaseOrder_throwsNotFound() {
        Vendor vendor = new Vendor();
        vendor.setId(5L);
        Employee employee = new Employee();
        employee.setId(7L);
        Project project = new Project();
        project.setId(9L);
        when(vendorRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.of(vendor));
        when(employeeRepository.findByIdAndOrganizationId(7L, ORG)).thenReturn(Optional.of(employee));
        when(projectRepository.findByIdAndOrganization_Id(9L, ORG)).thenReturn(Optional.of(project));
        when(purchaseOrderRepository.findByIdAndOrganization_Id(3L, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createGoodsReceivedNote(baseDto()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_buildsItemsSavesThemAndPublishesEvent() {
        stubMasterLookups();
        when(materialRepository.findByIdAndOrganization_Id(11L, ORG)).thenReturn(Optional.of(material(11L)));

        service.createGoodsReceivedNote(baseDto());

        // The line items are persisted with the received quantity and cost from the DTO,
        // each back-linked to the parent GRN.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GrnItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(grnItemRepository).saveAll(itemsCaptor.capture());
        List<GrnItem> items = itemsCaptor.getValue();
        assertThat(items).hasSize(1);
        GrnItem item = items.get(0);
        assertThat(item.getMaterial().getId()).isEqualTo(11L);
        assertThat(item.getReceivedQuantity()).isEqualTo(9);
        assertThat(item.getUnitCost()).isEqualByComparingTo("25.50");
        assertThat(item.getGoodsReceivedNote()).isNotNull();

        // The inventory-update trigger fires, carrying the saved GRN with its items attached.
        ArgumentCaptor<GrnCreatedEvent> eventCaptor = ArgumentCaptor.forClass(GrnCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        GoodsReceivedNote published = eventCaptor.getValue().getGoodsReceivedNote();
        assertThat(published.getGrnNumber()).isEqualTo("GRN-2026-000042");
        assertThat(published.getItems()).hasSize(1);
        assertThat(published.getVendor().getId()).isEqualTo(5L);
    }

    @Test
    void create_unknownStorageLocation_throwsNotFound() {
        stubMasterLookups();
        when(storageLocationRepository.findByIdAndOrganization_Id(88L, ORG)).thenReturn(Optional.empty());

        GoodsReceivedNoteCreationDto dto = baseDto();
        dto.setStorageLocationId(88L);

        assertThatThrownBy(() -> service.createGoodsReceivedNote(dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
