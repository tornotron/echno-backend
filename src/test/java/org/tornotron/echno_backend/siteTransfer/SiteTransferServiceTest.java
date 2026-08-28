package org.tornotron.echno_backend.siteTransfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransfer.mapper.SiteTransferMapper;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SiteTransferService. Repositories, the inventory service, the event
 * publisher, and the mapper are mocked; the entity graph is built in memory. The focus
 * is the logic this service owns: rejecting a duplicate transfer number, rejecting
 * unknown referenced entities, aggregating the per-material required quantities that feed
 * the sending-side stock check, choosing the location-scoped vs project-scoped variant of
 * that check, and building one transfer item per request line.
 */
@ExtendWith(MockitoExtension.class)
class SiteTransferServiceTest {

    private static final Long ORG = 100L;
    private static final Long SENDER = 7L;
    private static final Long SENDING_PROJECT = 9L;
    private static final Long RECEIVING_PROJECT = 10L;
    private static final Long SENDING_LOCATION = 3L;
    private static final Long MATERIAL = 11L;

    @Mock private SiteTransferRepository siteTransferRepository;
    @Mock private SiteTransferItemRepository siteTransferItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private InventoryService inventoryService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SiteTransferMapper siteTransferMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private DocumentNumberAllocator documentNumberAllocator;
    @Mock private TransactionRetryTemplate retryTemplate;

    private SiteTransferService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new SiteTransferService(siteTransferRepository, siteTransferItemRepository, userRepository,
                materialRepository, inventoryService, eventPublisher, siteTransferMapper, tenantEntityHelper,
                employeeRepository, projectRepository, storageLocationRepository,
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
        Employee sender = new Employee();
        sender.setId(SENDER);
        Project sending = new Project();
        sending.setId(SENDING_PROJECT);
        Project receiving = new Project();
        receiving.setId(RECEIVING_PROJECT);
        Organization org = new Organization();
        org.setId(ORG);
        lenient().when(documentNumberAllocator.allocate(DocumentNumberType.SITE_TRANSFER, ORG))
                .thenReturn("TRF-2026-000042");
        lenient().when(employeeRepository.findByIdAndOrganizationId(SENDER, ORG)).thenReturn(Optional.of(sender));
        lenient().when(projectRepository.findByIdAndOrganization_Id(SENDING_PROJECT, ORG)).thenReturn(Optional.of(sending));
        lenient().when(projectRepository.findByIdAndOrganization_Id(RECEIVING_PROJECT, ORG)).thenReturn(Optional.of(receiving));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(org);
        lenient().when(siteTransferRepository.save(any(SiteTransfer.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(materialRepository.findByIdAndOrganization_Id(eq(MATERIAL), eq(ORG)))
                .thenAnswer(inv -> {
                    Material m = new Material();
                    m.setId(MATERIAL);
                    return Optional.of(m);
                });
    }

    private SiteTransferItemDto item(Long materialId, int qty) {
        SiteTransferItemDto i = new SiteTransferItemDto();
        i.setMaterialId(materialId);
        i.setSentQuantity(qty);
        return i;
    }

    private SiteTransferCreationDto baseDto() {
        SiteTransferCreationDto dto = new SiteTransferCreationDto();
        dto.setIssueDate(LocalDateTime.now());
        dto.setSendingPerson(SENDER);
        dto.setSendingProjectId(SENDING_PROJECT);
        dto.setReceivingProjectId(RECEIVING_PROJECT);
        dto.setStatus("PENDING");
        dto.setItems(List.of(item(MATERIAL, 4)));
        return dto;
    }

    @Test
    void create_takesItsNumberFromTheServerNotTheCaller() {
        stubMasterLookups();

        service.createSiteTransfer(baseDto());

        ArgumentCaptor<SiteTransfer> captor = ArgumentCaptor.forClass(SiteTransfer.class);
        verify(siteTransferRepository).save(captor.capture());
        assertThat(captor.getValue().getTransferNumber()).isEqualTo("TRF-2026-000042");
        verify(documentNumberAllocator).allocate(DocumentNumberType.SITE_TRANSFER, ORG);
    }

    @Test
    void create_unknownSendingProject_throwsNotFound() {
        when(employeeRepository.findByIdAndOrganizationId(SENDER, ORG))
                .thenReturn(Optional.of(new Employee()));
        when(projectRepository.findByIdAndOrganization_Id(SENDING_PROJECT, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.createSiteTransfer(baseDto()));

        verify(siteTransferRepository, never()).save(any());
    }

    @Test
    void create_aggregatesQuantitiesOfRepeatedMaterial_forStockCheck() {
        stubMasterLookups();
        SiteTransferCreationDto dto = baseDto();
        // Same material on two lines: 4 + 6 must be summed to 10 for the single stock check.
        dto.setItems(List.of(item(MATERIAL, 4), item(MATERIAL, 6)));

        service.createSiteTransfer(dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, Double>> captor = ArgumentCaptor.forClass(Map.class);
        verify(inventoryService).validateSufficientStockForMultipleItems(captor.capture(), eq(SENDING_PROJECT));
        assertThat(captor.getValue()).containsEntry(MATERIAL, 10.0);
    }

    @Test
    void create_withSendingLocation_usesLocationScopedStockCheck() {
        stubMasterLookups();
        StorageLocation location = new StorageLocation();
        location.setId(SENDING_LOCATION);
        when(storageLocationRepository.findByIdAndOrganization_Id(SENDING_LOCATION, ORG)).thenReturn(Optional.of(location));

        SiteTransferCreationDto dto = baseDto();
        dto.setSendingStorageLocationId(SENDING_LOCATION);

        service.createSiteTransfer(dto);

        verify(inventoryService).validateSufficientStockForMultipleItemsAtLocation(any(), eq(SENDING_PROJECT), eq(SENDING_LOCATION));
        verify(inventoryService, never()).validateSufficientStockForMultipleItems(any(), anyLong());
    }

    @Test
    void create_happyPath_savesItemsParsesStatusAndPublishesEvent() {
        stubMasterLookups();

        service.createSiteTransfer(baseDto());

        ArgumentCaptor<SiteTransfer> transferCaptor = ArgumentCaptor.forClass(SiteTransfer.class);
        verify(siteTransferRepository).save(transferCaptor.capture());
        assertThat(transferCaptor.getValue().getStatus()).isEqualTo(SiteTransferStatus.PENDING);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SiteTransferItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(siteTransferItemRepository).saveAll(itemsCaptor.capture());
        List<SiteTransferItem> items = itemsCaptor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getMaterial().getId()).isEqualTo(MATERIAL);
        assertThat(items.get(0).getSiteTransfer()).isNotNull();

        verify(eventPublisher).publishEvent(any(SiteTransferCreatedEvent.class));
    }

    @Test
    void updateStatus_unknownTransfer_throwsNotFound() {
        when(siteTransferRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.updateSiteTransferStatus(5L, SiteTransferStatus.COMPLETED));
    }

    @Test
    void updateStatus_setsAndSaves() {
        SiteTransfer transfer = new SiteTransfer();
        transfer.setStatus(SiteTransferStatus.PENDING);
        when(siteTransferRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.of(transfer));

        service.updateSiteTransferStatus(5L, SiteTransferStatus.COMPLETED);

        assertThat(transfer.getStatus()).isEqualTo(SiteTransferStatus.COMPLETED);
        verify(siteTransferRepository).save(transfer);
    }
}
