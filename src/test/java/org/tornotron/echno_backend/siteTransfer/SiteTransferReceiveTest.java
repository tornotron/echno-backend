package org.tornotron.echno_backend.siteTransfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.events.SiteTransferCancelledEvent;
import org.tornotron.echno_backend.common.events.SiteTransferReceivedEvent;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCancellationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferReceiptDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferReceiptLineDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransfer.mapper.SiteTransferMapper;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * The second step of the two-step document: recording what the receiving site took delivery of,
 * and abandoning a transfer that never arrived.
 *
 * <p>Repositories, the reconciler's collaborator and the event publisher are mocked; the real
 * {@link SiteTransferReceiptReconciler} is used, because the point of most of these tests is the
 * wiring between the two rather than the arithmetic, which has its own test.
 */
@ExtendWith(MockitoExtension.class)
class SiteTransferReceiveTest {

    private static final Long ORG = 100L;
    private static final Long TRANSFER = 51L;
    private static final Long SENDING_PROJECT = 7L;
    private static final Long RECEIVING_PROJECT = 9L;

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
    private Organization organization;
    private Employee storekeeper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new SiteTransferService(siteTransferRepository, siteTransferItemRepository, userRepository,
                materialRepository, inventoryService, eventPublisher, siteTransferMapper, tenantEntityHelper,
                employeeRepository, projectRepository, storageLocationRepository,
                documentNumberAllocator, retryTemplate,
                new SiteTransferReceiptReconciler(statusTransitionRecorder),
                currentEmployeeService, userContextService, statusTransitionRecorder,
                statusTransitionRepository, statusTransitionMapper);
        // The template's own behaviour is covered by its own tests; here it just runs the work.
        lenient().when(retryTemplate.execute(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(1, Supplier.class).get());
        organization = new Organization();
        organization.setId(ORG);
        storekeeper = new Employee();
        storekeeper.setId(31L);
        storekeeper.setEmployeeName("Storekeeper");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SiteTransfer transfer(SiteTransferStatus status, Long receivingProjectId) {
        SiteTransfer transfer = new SiteTransfer();
        transfer.setId(TRANSFER);
        transfer.setTransferNumber("TRF-2026-000042");
        transfer.setStatus(status);
        Project sending = new Project();
        sending.setId(SENDING_PROJECT);
        Project receiving = new Project();
        receiving.setId(receivingProjectId);
        transfer.setSendingProject(sending);
        transfer.setReceivingProject(receiving);
        transfer.setOrganization(organization);
        transfer.setItems(new ArrayList<>());
        when(siteTransferRepository.findByIdAndOrganization_Id(TRANSFER, ORG)).thenReturn(Optional.of(transfer));
        return transfer;
    }

    private SiteTransferItem line(SiteTransfer transfer, Long id, int sent, Integer received) {
        Material material = new Material();
        material.setId(2L);
        material.setMaterialName("TMT Bar 12mm");
        SiteTransferItem item = new SiteTransferItem();
        item.setId(id);
        item.setSiteTransfer(transfer);
        item.setMaterial(material);
        item.setSentQuantity(sent);
        item.setReceivedQuantity(received);
        transfer.getItems().add(item);
        lenient().when(siteTransferItemRepository.lockBySiteTransferIdAndOrganizationId(TRANSFER, ORG))
                .thenReturn(transfer.getItems());
        return item;
    }

    private SiteTransferReceiptDto receipt(Long itemId, int quantity) {
        SiteTransferReceiptLineDto line = new SiteTransferReceiptLineDto();
        line.setItemId(itemId);
        line.setReceivedQuantity(quantity);
        SiteTransferReceiptDto dto = new SiteTransferReceiptDto();
        dto.setItems(List.of(line));
        return dto;
    }

    private void withSession() {
        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(storekeeper);
        User user = new User();
        user.setId(5L);
        lenient().when(userContextService.getCurrentUser()).thenReturn(user);
    }

    /**
     * The whole point of the second step: the inbound leg is raised now, by an event carrying
     * what arrived, rather than at creation when nobody had seen the lorry.
     */
    @Test
    void recordingAReceiptPublishesTheInboundLegForWhatArrived() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, null);
        withSession();

        service.receiveSiteTransfer(TRANSFER, receipt(84L, 8));

        ArgumentCaptor<SiteTransferReceivedEvent> captor =
                ArgumentCaptor.forClass(SiteTransferReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getReceivedLines()).singleElement()
                .satisfies(received -> {
                    assertThat(received.item()).isSameAs(line);
                    assertThat(received.quantity()).isEqualTo(8);
                });
        verify(siteTransferItemRepository).saveAll(transfer.getItems());
    }

    /**
     * Five caller-supplied actors were closed across the codebase for the same reason: a field
     * saying who did something is that person's statement, and an id off the payload is whatever
     * the caller typed. The receipt payload carries no actor at all.
     */
    @Test
    void takesThePersonConfirmingTheDeliveryFromTheSession() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);
        withSession();

        service.receiveSiteTransfer(TRANSFER, receipt(84L, 10));

        ArgumentCaptor<SiteTransferReceivedEvent> captor =
                ArgumentCaptor.forClass(SiteTransferReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getReceivedBy()).isSameAs(storekeeper);
        verify(currentEmployeeService).requireCurrentEmployee("record a site transfer as received");
    }

    /** An account with no employee record has nobody to be recorded as having seen the lorry. */
    @Test
    void refusesACallerWithNoEmployeeRecordBeforeAnyStockMoves() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);
        when(currentEmployeeService.requireCurrentEmployee(anyString()))
                .thenThrow(new AccessDeniedException("no employee record"));

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.receiveSiteTransfer(TRANSFER, receipt(84L, 10)));

        verify(eventPublisher, never()).publishEvent(any());
        verify(siteTransferItemRepository, never()).saveAll(any());
    }

    /**
     * Within one project both legs were posted at creation, so a receipt would raise the stock a
     * second time. The refusal says there was never a lorry rather than that the transfer is
     * already complete, which is the answer a storekeeper can act on.
     */
    @Test
    void refusesToReceiveATransferThatNeverLeftOneProject() {
        SiteTransfer transfer = transfer(SiteTransferStatus.COMPLETED, SENDING_PROJECT);
        line(transfer, 84L, 10, 10);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.receiveSiteTransfer(TRANSFER, receipt(84L, 10)))
                .withMessageContaining("never left that site's custody")
                .withMessageContaining("stock adjustment");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void refusesASecondReceiptAgainstACompletedTransfer() {
        SiteTransfer transfer = transfer(SiteTransferStatus.COMPLETED, RECEIVING_PROJECT);
        line(transfer, 84L, 10, 10);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.receiveSiteTransfer(TRANSFER, receipt(84L, 1)))
                .withMessageContaining("nothing left in transit");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void refusesAReceiptAgainstACancelledTransfer() {
        SiteTransfer transfer = transfer(SiteTransferStatus.CANCELLED, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.receiveSiteTransfer(TRANSFER, receipt(84L, 10)));

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Two people confirming the same lorry would otherwise each judge themselves against the
     * figure that stood before the other, and the second write would erase the first.
     */
    @Test
    void takesTheLinesUnderAWriteLock() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);
        withSession();

        service.receiveSiteTransfer(TRANSFER, receipt(84L, 10));

        verify(siteTransferItemRepository).lockBySiteTransferIdAndOrganizationId(TRANSFER, ORG);
        verify(siteTransferItemRepository, never()).findBySiteTransferId(anyLong());
    }

    /** A confirmation that nothing came is recorded on the line but posts no movement. */
    @Test
    void aReceiptOfNothingWritesNoInboundLeg() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, null);
        withSession();

        service.receiveSiteTransfer(TRANSFER, receipt(84L, 0));

        assertThat(line.getReceivedQuantity()).isZero();
        assertThat(transfer.getStatus()).isEqualTo(SiteTransferStatus.PENDING);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void receivingAnUnknownTransferIsNotFound() {
        when(siteTransferRepository.findByIdAndOrganization_Id(TRANSFER, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.receiveSiteTransfer(TRANSFER, receipt(84L, 1)));
    }

    /**
     * Without the reversal a transfer abandoned in transit leaves the sending project
     * permanently short with no way back, which makes the two-step document strictly worse than
     * the one-step one it replaces.
     */
    @Test
    void cancellingAPendingTransferReversesTheOutboundLeg() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, null);
        withSession();

        SiteTransferCancellationDto dto = new SiteTransferCancellationDto();
        dto.setReason("Lorry turned back at the gate");
        service.cancelSiteTransfer(TRANSFER, dto);

        assertThat(transfer.getStatus()).isEqualTo(SiteTransferStatus.CANCELLED);
        ArgumentCaptor<SiteTransferCancelledEvent> captor =
                ArgumentCaptor.forClass(SiteTransferCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getItems()).containsExactly(line);
        assertThat(captor.getValue().getCancelledBy()).isSameAs(storekeeper);
        assertThat(captor.getValue().getReason()).isEqualTo("Lorry turned back at the gate");
    }

    @Test
    void cancellingRecordsWhyOnTheStatusTrail() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);
        withSession();

        SiteTransferCancellationDto dto = new SiteTransferCancellationDto();
        dto.setReason("Lorry turned back at the gate");
        service.cancelSiteTransfer(TRANSFER, dto);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(statusTransitionRecorder).recordChange(
                eq(SiteTransferService.HISTORY_ENTITY_TYPE), eq(TRANSFER), eq(organization),
                eq("PENDING"), eq("CANCELLED"), any(), note.capture());
        assertThat(note.getValue()).contains("Lorry turned back at the gate");
    }

    /**
     * Part of the material is standing at the far site, so reversing the whole outbound leg
     * would claim it came back. What to do about the rest is a decision, not a reversal.
     */
    @Test
    void refusesToCancelATransferThatHasAlreadyHadSomethingReceived() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PARTIALLY_TRANSFERRED, RECEIVING_PROJECT);
        line(transfer, 84L, 10, 8);

        SiteTransferCancellationDto dto = new SiteTransferCancellationDto();
        dto.setReason("Changed our minds");

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.cancelSiteTransfer(TRANSFER, dto))
                .withMessageContaining("cannot be cancelled")
                .withMessageContaining("stock adjustment");

        assertThat(transfer.getStatus()).isEqualTo(SiteTransferStatus.PARTIALLY_TRANSFERRED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** A transfer inside one project was complete when it was written; correcting it is an adjustment. */
    @Test
    void refusesToCancelATransferThatNeverLeftOneProject() {
        SiteTransfer transfer = transfer(SiteTransferStatus.COMPLETED, SENDING_PROJECT);

        SiteTransferCancellationDto dto = new SiteTransferCancellationDto();
        dto.setReason("Changed our minds");

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.cancelSiteTransfer(TRANSFER, dto));

        verify(eventPublisher, never()).publishEvent(any());
    }
}
