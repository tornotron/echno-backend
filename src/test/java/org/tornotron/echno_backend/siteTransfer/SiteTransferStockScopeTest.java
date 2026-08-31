package org.tornotron.echno_backend.siteTransfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.exception.InsufficientStockException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStock;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransfer.mapper.SiteTransferMapper;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sending-side stock check against a real {@link InventoryService} over a mocked stock
 * repository, so the balance row the check reads is the thing under test rather than a
 * mock's say-so.
 *
 * <p>A transfer that names no sending storage location debits the sending project's
 * unlocated row. Checking the project total instead authorises a draw against stock sitting
 * in storage locations the debit never reaches, and the unlocated row goes negative. That is
 * how {@code current_stock} row 15 on staging reached -30 through the consumption path
 * before #539.
 */
@ExtendWith(MockitoExtension.class)
class SiteTransferStockScopeTest {

    private static final Long ORG = 100L;
    private static final Long SENDER = 7L;
    private static final Long SENDING_PROJECT = 9L;
    private static final Long RECEIVING_PROJECT = 10L;
    private static final Long SENDING_LOCATION = 3L;
    private static final Long MATERIAL = 11L;
    private static final Long OTHER_MATERIAL = 12L;

    @Mock private SiteTransferRepository siteTransferRepository;
    @Mock private SiteTransferItemRepository siteTransferItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private CurrentStockRepository currentStockRepository;
    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SiteTransferMapper siteTransferMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private DocumentNumberAllocator documentNumberAllocator;
    @Mock private TransactionRetryTemplate retryTemplate;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private UserContextService userContextService;
    @Mock private StatusTransitionRecorder statusTransitionRecorder;
    @Mock private StatusTransitionRepository statusTransitionRepository;
    @Mock private StatusTransitionMapper statusTransitionMapper;

    private SiteTransferService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        InventoryService inventoryService = new InventoryService(currentStockRepository,
                inventoryTransactionRepository, materialRepository, storageLocationRepository);
        service = new SiteTransferService(siteTransferRepository, siteTransferItemRepository, userRepository,
                materialRepository, inventoryService, eventPublisher, siteTransferMapper, tenantEntityHelper,
                employeeRepository, projectRepository, storageLocationRepository,
                documentNumberAllocator, retryTemplate, new SiteTransferReceiptReconciler(statusTransitionRecorder),
                currentEmployeeService, userContextService, statusTransitionRecorder,
                statusTransitionRepository, statusTransitionMapper);
        lenient().when(retryTemplate.execute(anyString(), any(Predicate.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());

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
        lenient().when(materialRepository.findByIdAndOrganization_Id(any(), eq(ORG)))
                .thenAnswer(inv -> {
                    Material m = new Material();
                    m.setId(inv.getArgument(0));
                    return Optional.of(m);
                });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CurrentStock stockRow(double quantity) {
        CurrentStock row = new CurrentStock();
        row.setCurrentQuantity(quantity);
        return row;
    }

    private SiteTransferItemDto item(Long materialId, int qty) {
        SiteTransferItemDto i = new SiteTransferItemDto();
        i.setMaterialId(materialId);
        i.setSentQuantity(qty);
        return i;
    }

    private SiteTransferCreationDto dto(Long sendingLocationId) {
        SiteTransferCreationDto dto = new SiteTransferCreationDto();
        dto.setIssueDate(LocalDateTime.now());
        dto.setSendingPerson(SENDER);
        dto.setSendingProjectId(SENDING_PROJECT);
        dto.setReceivingProjectId(RECEIVING_PROJECT);
        dto.setSendingStorageLocationId(sendingLocationId);
        dto.setStatus(SiteTransferStatus.PENDING);
        dto.setItems(List.of(item(MATERIAL, 4)));
        return dto;
    }

    private void sendingLocationExists() {
        StorageLocation location = new StorageLocation();
        location.setId(SENDING_LOCATION);
        Project owner = new Project();
        owner.setId(SENDING_PROJECT);
        location.setProject(owner);
        lenient().when(storageLocationRepository.findByIdAndOrganization_Id(SENDING_LOCATION, ORG))
                .thenReturn(Optional.of(location));
    }

    @Test
    void aSendingLocationBelongingToAnotherProjectIsRefusedEvenWhenItAlreadyHoldsABalance() {
        // The stock-adjustment path accepts a location a balance already sits at, because
        // correcting that pairing is what an adjustment is for. A transfer records a new
        // movement, so the strict rule still applies here whether a balance row exists or not.
        StorageLocation location = new StorageLocation();
        location.setId(SENDING_LOCATION);
        Project owner = new Project();
        owner.setId(RECEIVING_PROJECT);
        location.setProject(owner);
        when(storageLocationRepository.findByIdAndOrganization_Id(SENDING_LOCATION, ORG))
                .thenReturn(Optional.of(location));
        lenient().when(currentStockRepository
                        .findByMaterialIdAndProjectIdAndStorageLocationId(MATERIAL, SENDING_PROJECT, SENDING_LOCATION))
                .thenReturn(Optional.of(stockRow(60.0)));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.createSiteTransfer(dto(SENDING_LOCATION)))
                .withMessageContaining("belongs to project with ID " + RECEIVING_PROJECT);

        verify(siteTransferRepository, never()).save(any());
    }

    @Test
    void aTransferWithNoSendingLocationIsRefusedWhenTheProjectHoldsItsStockInsideLocations() {
        // The project total is 60, all of it inside storage locations. The debit writes the
        // project's unlocated row, which holds nothing.
        lenient().when(currentStockRepository.sumCurrentQuantityByMaterialAndProject(MATERIAL, SENDING_PROJECT))
                .thenReturn(60.0);
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationIsNull(MATERIAL, SENDING_PROJECT))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.createSiteTransfer(dto(null)))
                .withMessageContaining("outside a storage location")
                .withMessageContaining("name the location");

        verify(siteTransferRepository, never()).save(any());
    }

    @Test
    void aTransferWithNoSendingLocationIsRefusedWhenTheUnlocatedBalanceIsShort() {
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationIsNull(MATERIAL, SENDING_PROJECT))
                .thenReturn(Optional.of(stockRow(1.0)));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.createSiteTransfer(dto(null)))
                .withMessageContaining("Required 4.00, Available 1.00");

        verify(siteTransferRepository, never()).save(any());
    }

    @Test
    void aTransferWithNoSendingLocationIsAllowedAgainstTheUnlocatedBalance() {
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationIsNull(MATERIAL, SENDING_PROJECT))
                .thenReturn(Optional.of(stockRow(4.0)));

        assertThatCode(() -> service.createSiteTransfer(dto(null))).doesNotThrowAnyException();

        verify(siteTransferRepository).save(any());
    }

    @Test
    void everyShortMaterialIsNamedRatherThanOnlyTheFirst() {
        SiteTransferCreationDto dto = dto(null);
        dto.setItems(List.of(item(MATERIAL, 4), item(OTHER_MATERIAL, 5)));
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationIsNull(MATERIAL, SENDING_PROJECT))
                .thenReturn(Optional.of(stockRow(1.0)));
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationIsNull(OTHER_MATERIAL, SENDING_PROJECT))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.createSiteTransfer(dto))
                .withMessageContaining("Material ID 11")
                .withMessageContaining("Material ID 12");
    }

    @Test
    void aTransferFromANamedLocationStillReadsThatLocationsBalance() {
        sendingLocationExists();
        when(currentStockRepository.findByMaterialIdAndProjectIdAndStorageLocationId(
                MATERIAL, SENDING_PROJECT, SENDING_LOCATION)).thenReturn(Optional.of(stockRow(4.0)));

        assertThatCode(() -> service.createSiteTransfer(dto(SENDING_LOCATION))).doesNotThrowAnyException();

        verify(siteTransferRepository).save(any());
        verify(currentStockRepository, never())
                .findByMaterialIdAndProjectIdAndStorageLocationIsNull(any(), any());
    }
}
