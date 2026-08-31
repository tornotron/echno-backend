package org.tornotron.echno_backend.siteTransfer;

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
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
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
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
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
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A transfer is issued as PENDING and nothing else. The other two states say the receiving
 * site has taken delivery, which is recorded through the transfer's own status endpoint, and
 * that endpoint is held by a different authority from create. Stock moves on creation either
 * way, so a transfer issued as COMPLETED would stand as a movement confirmed by nobody.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class SiteTransferCreateStatusTest {

    private static final Long ORG = 100L;
    private static final Long SENDER = 7L;
    private static final Long SENDING_PROJECT = 9L;
    private static final Long RECEIVING_PROJECT = 10L;
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
        service = new SiteTransferService(siteTransferRepository, siteTransferItemRepository, userRepository,
                materialRepository, inventoryService, eventPublisher, siteTransferMapper, tenantEntityHelper,
                employeeRepository, projectRepository, storageLocationRepository,
                documentNumberAllocator, retryTemplate, new SiteTransferReceiptReconciler(statusTransitionRecorder),
                currentEmployeeService, userContextService, statusTransitionRecorder,
                statusTransitionRepository, statusTransitionMapper);
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
                    Material material = new Material();
                    material.setId(MATERIAL);
                    return Optional.of(material);
                });
    }

    private SiteTransferCreationDto dto(SiteTransferStatus status) {
        SiteTransferCreationDto dto = new SiteTransferCreationDto();
        dto.setIssueDate(LocalDateTime.now());
        dto.setSendingPerson(SENDER);
        dto.setSendingProjectId(SENDING_PROJECT);
        dto.setReceivingProjectId(RECEIVING_PROJECT);
        dto.setStatus(status);
        SiteTransferItemDto item = new SiteTransferItemDto();
        item.setMaterialId(MATERIAL);
        item.setSentQuantity(4);
        dto.setItems(List.of(item));
        return dto;
    }

    @Test
    void create_refusesATransferIssuedAlreadyCompleted() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.createSiteTransfer(dto(SiteTransferStatus.COMPLETED)))
                .withMessageContaining("cannot be created as COMPLETED");

        verify(siteTransferRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(SiteTransferCreatedEvent.class));
    }

    @Test
    void create_refusesATransferIssuedAlreadyPartiallyTransferred() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.createSiteTransfer(dto(SiteTransferStatus.PARTIALLY_TRANSFERRED)))
                .withMessageContaining("cannot be created as PARTIALLY_TRANSFERRED");

        verify(siteTransferRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(SiteTransferCreatedEvent.class));
    }

    @Test
    void create_refusesEveryStartingStateButPending() {
        // Both later states are recorded through PATCH /{id}/status, so neither is a state the
        // caller raising the transfer could have reached by asking for it.
        for (SiteTransferStatus status : SiteTransferStatus.values()) {
            if (status == SiteTransferStatus.PENDING) {
                continue;
            }
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.createSiteTransfer(dto(status)))
                    .withMessageContaining("cannot be created as " + status);
        }
        verify(siteTransferRepository, never()).save(any());
    }

    @Test
    void create_withNoStatusInThePayload_startsPending() {
        stubMasterLookups();

        service.createSiteTransfer(dto(null));

        ArgumentCaptor<SiteTransfer> captor = ArgumentCaptor.forClass(SiteTransfer.class);
        verify(siteTransferRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SiteTransferStatus.PENDING);
    }

    @Test
    void create_withPendingInThePayload_isAccepted() {
        stubMasterLookups();

        service.createSiteTransfer(dto(SiteTransferStatus.PENDING));

        ArgumentCaptor<SiteTransfer> captor = ArgumentCaptor.forClass(SiteTransfer.class);
        verify(siteTransferRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SiteTransferStatus.PENDING);
        verify(eventPublisher).publishEvent(any(SiteTransferCreatedEvent.class));
    }
}
