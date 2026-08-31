package org.tornotron.echno_backend.stockAdjustment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentCreationDto;
import org.tornotron.echno_backend.stockAdjustment.mapper.StockAdjustmentMapper;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserNameDirectory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Approving a stock adjustment is the only way to set or correct a balance, so this covers
 * what that has to guarantee: a ledger entry carrying the reason is written before the
 * balance moves, a physical count lands the balance on the counted figure, a movement with
 * no stated reason is refused, an approval whose balance has moved since the document was
 * raised is refused so that what is approved is what was raised, an adjustment cannot be
 * posted twice or edited afterwards,
 * and it cannot be used to book stock onto a location belonging to another project that
 * holds no balance for it, or to push a balance below zero. The one relaxation the
 * adjustment path carries is covered too: a balance that already sits on another project's
 * location can be corrected, because that pairing is what the document exists to fix. Segregation of duties is covered here too: the person who
 * raised the document cannot approve it, and a system administrator who does is recorded
 * as having self-approved on the ledger entry itself.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentApprovalTest {

    private static final Long ORG = 100L;
    private static final Long ADJUSTMENT = 5L;
    private static final Long MATERIAL = 8L;
    private static final Long PROJECT = 7L;
    private static final Long LOCATION = 14L;
    private static final Long OTHER_PROJECT = 9L;
    private static final Long APPROVER = 42L;
    private static final Long DRAFTER = 43L;

    @Mock private StockAdjustmentRepository stockAdjustmentRepository;
    @Mock private UserNameDirectory userNameDirectory;
    @Mock private StockAdjustmentMapper stockAdjustmentMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private MaterialRepository materialRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private InventoryService inventoryService;
    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private UserContextService userContextService;
    @Mock private OrganizationSecurityService orgSecurity;

    private StockAdjustmentService service;
    private Organization organization;
    private Project project;
    private Material material;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new StockAdjustmentService(stockAdjustmentRepository, stockAdjustmentMapper,
                tenantEntityHelper, materialRepository, storageLocationRepository, projectRepository,
                inventoryService, inventoryTransactionRepository, userContextService,
                new SelfApprovalPolicy(orgSecurity), userNameDirectory);

        organization = new Organization();
        organization.setId(ORG);
        project = new Project();
        project.setId(PROJECT);
        material = new Material();
        material.setId(MATERIAL);

        lenient().when(userContextService.getCurrentUserId()).thenReturn(APPROVER);
        lenient().when(stockAdjustmentRepository.saveAndFlush(any(StockAdjustment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(inventoryService.getAverageCost(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private StorageLocation location(Long id, Long owningProjectId) {
        StorageLocation location = new StorageLocation();
        location.setId(id);
        if (owningProjectId != null) {
            Project owner = new Project();
            owner.setId(owningProjectId);
            location.setProject(owner);
        }
        return location;
    }

    private StockAdjustment adjustment(StorageLocation headerLocation) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setId(ADJUSTMENT);
        adjustment.setAdjustmentNumber("SA-2026-0005");
        adjustment.setOrganization(organization);
        adjustment.setProject(project);
        adjustment.setLocation(headerLocation);
        adjustment.setPrimaryReason("physical_count");
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(ADJUSTMENT, ORG))
                .thenReturn(Optional.of(adjustment));
        return adjustment;
    }

    private StockAdjustmentLineItem line(StockAdjustment adjustment, Double physical, Double delta, String reason) {
        StockAdjustmentLineItem line = new StockAdjustmentLineItem();
        line.setMaterial(material);
        line.setPhysicalQuantity(physical);
        line.setAdjustmentQuantity(delta);
        line.setReason(reason);
        line.setOrganization(organization);
        adjustment.addLineItem(line);
        return line;
    }

    private void balanceAtLocation(double quantity) {
        when(inventoryService.findStockAtLocation(MATERIAL, PROJECT, LOCATION))
                .thenReturn(Optional.of(quantity));
    }

    @Test
    void aCountedLineWritesALedgerEntryCarryingItsReasonAndLandsTheBalanceOnTheCount() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        StockAdjustmentLineItem line = line(adjustment, 46.0, null, "damage");
        line.setReasonDetails("bags damaged by moisture");
        balanceAtLocation(48.0);

        service.approve(ADJUSTMENT);

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        InventoryTransaction posted = captor.getValue();
        assertThat(posted.getTransactionType()).isEqualTo(InventoryTransactionType.ADJUST);
        assertThat(posted.getReferenceNumber()).isEqualTo("SA-2026-0005");
        assertThat(posted.getOpeningStock()).isEqualTo(48.0);
        assertThat(posted.getQuantityChanged()).isEqualTo(-2.0);
        assertThat(posted.getClosingStock()).isEqualTo(46.0);
        assertThat(posted.getRemarks()).contains("damage").contains("bags damaged by moisture");
        assertThat(posted.getProject()).isSameAs(project);

        verify(inventoryService).updateCurrentStock(eq(material), eq(project), any(StorageLocation.class),
                eq(organization), eq(-2.0), isNull());
        assertThat(line.getSystemQuantity()).isEqualTo(48.0);
        assertThat(line.getAdjustmentQuantity()).isEqualTo(-2.0);
        assertThat(adjustment.getTotalVarianceQuantity()).isEqualTo(-2.0);
        assertThat(adjustment.getStatus()).isEqualTo("processed");
        assertThat(adjustment.getApprovedBy()).isEqualTo(APPROVER);
        assertThat(adjustment.getProcessedBy()).isEqualTo(APPROVER);
        assertThat(adjustment.getProcessedAt()).isNotNull();
    }

    @Test
    void aCountedLineIsRefusedWhenTheBalanceHasMovedSinceTheDocumentWasRaised() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        StockAdjustmentLineItem line = line(adjustment, 46.0, null, "damage");
        // The count sheet was raised when the system said 48; a receipt has since taken it to 50.
        line.setSystemQuantity(48.0);
        balanceAtLocation(50.0);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("48.0")
                .withMessageContaining("50.0");

        // Posting it would have driven the balance to the counted 46, quietly taking back the
        // receipt that moved it. Nothing reaches the ledger and no balance moves.
        verify(inventoryTransactionRepository, never()).save(any());
        verify(inventoryService, never()).updateCurrentStock(any(), any(), any(), any(), any(), any());
        assertThat(adjustment.getProcessedAt()).isNull();
        assertThat(adjustment.getStatus()).isNotEqualTo("processed");
        // And the figures on the document are still the ones it was raised with, so what the
        // window did is still readable afterwards.
        assertThat(line.getSystemQuantity()).isEqualTo(48.0);
    }

    @Test
    void aSignedDeltaLineIsRefusedWhenTheBalanceHasMovedToo() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        StockAdjustmentLineItem line = line(adjustment, null, 30.0, "data correction");
        // A line that states no count, only "book 30 more here". Raised when the location stood
        // at 400, so the approver is agreeing to a closing figure of 430; it now stands at 420,
        // and posting would land on 450 instead. The closing figure stays well clear of zero, so
        // nothing but the moved balance can be what refuses this.
        line.setSystemQuantity(400.0);
        balanceAtLocation(420.0);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("400.0")
                .withMessageContaining("420.0");

        verify(inventoryTransactionRepository, never()).save(any());
        verify(inventoryService, never()).updateCurrentStock(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anApprovalPostsTheFiguresTheDocumentWasRaisedWith() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        StockAdjustmentLineItem line = line(adjustment, 46.0, -2.0, "damage");
        line.setSystemQuantity(48.0);
        balanceAtLocation(48.0);

        service.approve(ADJUSTMENT);

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getOpeningStock()).isEqualTo(48.0);
        assertThat(captor.getValue().getQuantityChanged()).isEqualTo(-2.0);
        assertThat(captor.getValue().getClosingStock()).isEqualTo(46.0);
        // Approval changed neither figure on the line, so the document that was approved is the
        // document that was raised and the ledger entry describes the same movement it does.
        assertThat(line.getSystemQuantity()).isEqualTo(48.0);
        assertThat(line.getPhysicalQuantity()).isEqualTo(46.0);
        assertThat(line.getAdjustmentQuantity()).isEqualTo(-2.0);
    }

    @Test
    void aBalanceMovedOnlyByFloatingPointResidueIsNotTreatedAsMoved() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        StockAdjustmentLineItem line = line(adjustment, 46.0, null, "damage");
        line.setSystemQuantity(48.0);
        balanceAtLocation(48.0 + 1e-12);

        service.approve(ADJUSTMENT);

        verify(inventoryTransactionRepository).save(any());
    }

    @Test
    void aLineRaisedWithNoOpeningBalanceIsPostedAndHasItsFiguresFilledIn() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        // Raised before the opening balance was stamped onto lines: there is nothing to compare,
        // so there is nothing the approver can be said to have disagreed with.
        StockAdjustmentLineItem line = line(adjustment, 46.0, null, "damage");
        balanceAtLocation(50.0);

        service.approve(ADJUSTMENT);

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantityChanged()).isEqualTo(-4.0);
        assertThat(line.getSystemQuantity()).isEqualTo(50.0);
        assertThat(line.getAdjustmentQuantity()).isEqualTo(-4.0);
    }

    @Test
    void aNegativeBalanceCanBeCorrectedBackToZero() {
        // Row 15 on staging: material 8, project 7, no storage location, quantity -30.
        StockAdjustment adjustment = adjustment(null);
        line(adjustment, 0.0, null, "correcting a negative balance");
        when(inventoryService.findUnlocatedStock(MATERIAL, PROJECT)).thenReturn(Optional.of(-30.0));

        service.approve(ADJUSTMENT);

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantityChanged()).isEqualTo(30.0);
        assertThat(captor.getValue().getClosingStock()).isEqualTo(0.0);
        verify(inventoryService).updateCurrentStock(eq(material), eq(project), isNull(), eq(organization),
                eq(30.0), any(BigDecimal.class));
    }

    @Test
    void aMovementWithNoReasonAnywhereIsRefused() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        adjustment.setPrimaryReason(null);
        line(adjustment, null, -5.0, null);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("must say why it happened");

        verify(inventoryTransactionRepository, never()).save(any());
        verify(inventoryService, never()).updateCurrentStock(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aLineOnAnotherProjectsLocationHoldingNoBalanceIsRefused() {
        // Nothing has ever been booked here, so there is no wrong pairing to correct and the
        // adjustment would be inventing one. The strict rule still applies.
        StockAdjustment adjustment = adjustment(location(LOCATION, OTHER_PROJECT));
        line(adjustment, null, -5.0, "damage");
        when(inventoryService.findStockAtLocation(MATERIAL, PROJECT, LOCATION))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("belongs to project with ID 9")
                .withMessageContaining("holds no balance");

        verify(inventoryTransactionRepository, never()).save(any());
        verify(inventoryService, never()).updateCurrentStock(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBalanceAlreadySittingOnAnotherProjectsLocationCanBeCorrected() {
        // Issue #563. current_stock row 6 on staging is material 8 at project 7, storage
        // location 2, holding 400; location 2 belongs to project 3. The wrong pairing is the
        // thing being fixed, so the adjustment path has to reach it. MC-1 took 30 out of it,
        // so the true figure is 370.
        StockAdjustment adjustment = adjustment(null);
        StockAdjustmentLineItem line = line(adjustment, 370.0, null, "Data correction");
        line.setLocation(location(LOCATION, OTHER_PROJECT));
        balanceAtLocation(400.0);

        service.approve(ADJUSTMENT);

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        InventoryTransaction posted = captor.getValue();
        assertThat(posted.getOpeningStock()).isEqualTo(400.0);
        assertThat(posted.getQuantityChanged()).isEqualTo(-30.0);
        assertThat(posted.getClosingStock()).isEqualTo(370.0);
        assertThat(posted.getStorageLocation().getId()).isEqualTo(LOCATION);
        verify(inventoryService).updateCurrentStock(eq(material), eq(project), any(StorageLocation.class),
                eq(organization), eq(-30.0), isNull());
    }

    @Test
    void anAdjustmentThatWouldTakeABalanceBelowZeroIsRefused() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        line(adjustment, null, -20.0, "write off");
        balanceAtLocation(5.0);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("cannot take it below zero");

        verify(inventoryTransactionRepository, never()).save(any());
    }

    @Test
    void aLineThatComesOutAtNoMovementWritesNoLedgerEntry() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        line(adjustment, 48.0, null, "physical count");
        balanceAtLocation(48.0);

        service.approve(ADJUSTMENT);

        verify(inventoryTransactionRepository, never()).save(any());
        verify(inventoryService, never()).updateCurrentStock(any(), any(), any(), any(), any(), any());
        assertThat(adjustment.getProcessedAt()).isNotNull();
    }

    @Test
    void anAdjustmentWithNoProjectHasNoBalanceToCorrect() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        adjustment.setProject(null);
        line(adjustment, null, -5.0, "damage");

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("names no project");
    }

    @Test
    void aPostedAdjustmentCannotBePostedEditedOrDeletedAgain() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        line(adjustment, null, -5.0, "damage");
        adjustment.setProcessedAt(LocalDateTime.now());

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("posted again");
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.update(ADJUSTMENT, new StockAdjustmentCreationDto()))
                .withMessageContaining("cannot be edited");
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.delete(ADJUSTMENT))
                .withMessageContaining("cannot be deleted");

        verify(inventoryTransactionRepository, never()).save(any());
        verify(stockAdjustmentRepository, never()).delete(any());
    }

    @Test
    void theSamePersonCannotRaiseAndApproveTheDocumentAndNothingIsPosted() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        adjustment.setSubmittedBy(APPROVER);
        line(adjustment, 46.0, null, "damage");
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("someone other than whoever raised the document");

        verify(inventoryTransactionRepository, never()).save(any());
        verify(inventoryService, never()).updateCurrentStock(any(), any(), any(), any(), any(), any());
        assertThat(adjustment.getProcessedAt()).isNull();
        assertThat(adjustment.getApprovedBy()).isNull();
        assertThat(adjustment.getStatus()).isNotEqualTo("processed");
    }

    @Test
    void aSystemAdministratorApprovingTheirOwnDocumentPostsAndTheLedgerEntrySaysSo() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        adjustment.setSubmittedBy(APPROVER);
        line(adjustment, 46.0, null, "damage");
        balanceAtLocation(48.0);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(true);

        service.approve(ADJUSTMENT);

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getRemarks()).contains("damage").contains("self-approved");
        assertThat(adjustment.getApprovedBy()).isEqualTo(APPROVER);
        assertThat(adjustment.getProcessedAt()).isNotNull();
    }

    @Test
    void anApprovalByAnyoneOtherThanTheRaiserPostsWithNoSelfApprovalOnTheLedger() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        adjustment.setSubmittedBy(DRAFTER);
        line(adjustment, 46.0, null, "damage");
        balanceAtLocation(48.0);

        service.approve(ADJUSTMENT);

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getRemarks()).doesNotContain("self-approved");
        assertThat(adjustment.getApprovedBy()).isEqualTo(APPROVER);
    }

    @Test
    void aDocumentRaisedBeforeTheRaiserWasRecordedCanStillBeApproved() {
        StockAdjustment adjustment = adjustment(location(LOCATION, PROJECT));
        adjustment.setSubmittedBy(null);
        line(adjustment, 46.0, null, "damage");
        balanceAtLocation(48.0);

        service.approve(ADJUSTMENT);

        verify(inventoryTransactionRepository).save(any(InventoryTransaction.class));
        assertThat(adjustment.getApprovedBy()).isEqualTo(APPROVER);
    }
}
