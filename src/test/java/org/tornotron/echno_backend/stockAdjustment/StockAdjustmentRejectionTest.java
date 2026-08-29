package org.tornotron.echno_backend.stockAdjustment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentCreationDto;
import org.tornotron.echno_backend.stockAdjustment.mapper.StockAdjustmentMapper;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Rejecting a stock adjustment is the other way a draft leaves the pending state, and the only
 * one that keeps the refusal on the record: deleting the document takes the proposed correction
 * and the grounds for turning it down away with it.
 *
 * <p>This pins what the transition has to guarantee. It stamps who refused the document, when and
 * why, and moves it to the rejected status. It writes nothing to the stock ledger and moves no
 * balance, which is what separates it from approval. It insists on a reason, on the same footing
 * as the reason every posted movement has to carry. It refuses a document already posted, whose
 * lines are on the ledger. It leaves the document read-only afterwards, because there is one set
 * of rejection columns and reopening the document would overwrite the refusal.
 *
 * <p>It is also deliberately outside {@link SelfApprovalPolicy}: that rule is the second pair of
 * eyes on the entry an approval posts, and a rejection posts none. The test below verifies the
 * policy is not consulted at all rather than that it happens to pass.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentRejectionTest {

    private static final Long ORG = 100L;
    private static final Long ADJUSTMENT = 5L;
    private static final Long APPROVER = 42L;
    private static final Long DRAFTER = 43L;

    @Mock private StockAdjustmentRepository stockAdjustmentRepository;
    @Mock private StockAdjustmentMapper stockAdjustmentMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private MaterialRepository materialRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private InventoryService inventoryService;
    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private UserContextService userContextService;
    @Mock private SelfApprovalPolicy selfApprovalPolicy;

    private StockAdjustmentService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new StockAdjustmentService(stockAdjustmentRepository, stockAdjustmentMapper,
                tenantEntityHelper, materialRepository, storageLocationRepository, projectRepository,
                inventoryService, inventoryTransactionRepository, userContextService,
                selfApprovalPolicy);

        organization = new Organization();
        organization.setId(ORG);

        lenient().when(userContextService.getCurrentUserId()).thenReturn(APPROVER);
        lenient().when(stockAdjustmentRepository.saveAndFlush(any(StockAdjustment.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A draft raised by someone else, with one line, found in this organization. */
    private StockAdjustment draftRaisedBy(Long raiser) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setId(ADJUSTMENT);
        adjustment.setAdjustmentNumber("SA-2026-0005");
        adjustment.setOrganization(organization);
        adjustment.setStatus("draft");
        adjustment.setSubmittedBy(raiser);
        adjustment.setPrimaryReason("physical_count");

        Project project = new Project();
        project.setId(7L);
        adjustment.setProject(project);

        Material material = new Material();
        material.setId(8L);
        StockAdjustmentLineItem line = new StockAdjustmentLineItem();
        line.setMaterial(material);
        line.setPhysicalQuantity(46.0);
        line.setOrganization(organization);
        adjustment.addLineItem(line);

        lenient().when(stockAdjustmentRepository.lockByIdAndOrganizationId(ADJUSTMENT, ORG))
                .thenReturn(Optional.of(adjustment));
        return adjustment;
    }

    @Test
    void rejectingADraftRecordsWhoRefusedItWhenAndWhy() {
        StockAdjustment adjustment = draftRaisedBy(DRAFTER);

        service.reject(ADJUSTMENT, "Variance not supported by the count sheet");

        assertThat(adjustment.getStatus()).isEqualTo("rejected");
        assertThat(adjustment.getRejectedBy()).isEqualTo(APPROVER);
        assertThat(adjustment.getRejectedAt()).isNotNull();
        assertThat(adjustment.getRejectionReason()).isEqualTo("Variance not supported by the count sheet");
        verify(stockAdjustmentRepository).saveAndFlush(adjustment);
    }

    @Test
    void aRejectionPostsNothingToTheLedgerAndMovesNoBalance() {
        StockAdjustment adjustment = draftRaisedBy(DRAFTER);

        service.reject(ADJUSTMENT, "Count sheet unsigned");

        verifyNoInteractions(inventoryTransactionRepository);
        verify(inventoryService, never()).updateCurrentStock(any(), any(), any(), any(), any(), any());
        assertThat(adjustment.getApprovedBy()).isNull();
        assertThat(adjustment.getProcessedAt()).isNull();
    }

    /**
     * The reason is what a rejection is for. Without it the document records only that somebody
     * said no, which is already readable from the absent approval.
     */
    @Test
    void aRejectionWithNoReasonIsRefused() {
        StockAdjustment adjustment = draftRaisedBy(DRAFTER);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.reject(ADJUSTMENT, "   "))
                .withMessageContaining("needs a reason");

        assertThat(adjustment.getRejectedAt()).isNull();
        assertThat(adjustment.getStatus()).isEqualTo("draft");
    }

    @Test
    void aNullReasonIsRefusedTheSameWay() {
        draftRaisedBy(DRAFTER);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.reject(ADJUSTMENT, null));
    }

    @Test
    void theReasonIsStoredTrimmed() {
        StockAdjustment adjustment = draftRaisedBy(DRAFTER);

        service.reject(ADJUSTMENT, "  Variance not supported  ");

        assertThat(adjustment.getRejectionReason()).isEqualTo("Variance not supported");
    }

    /**
     * Rejecting your own document is left outside the self-approval rule. It posts no entry for a
     * second pair of eyes to check, and whoever raised it could delete the draft outright anyway,
     * so refusing the rejection would only push them to the outcome that keeps no record. Same
     * reading the attendance path settled on for withdrawing your own request.
     */
    @Test
    void rejectingYourOwnAdjustmentIsNotSubjectToTheSelfApprovalRule() {
        StockAdjustment adjustment = draftRaisedBy(APPROVER);

        service.reject(ADJUSTMENT, "Raised against the wrong location");

        verify(selfApprovalPolicy, never()).checkSelfApproval(any(), any(), any());
        assertThat(adjustment.getStatus()).isEqualTo("rejected");
        assertThat(adjustment.getRejectedBy()).isEqualTo(APPROVER);
    }

    /**
     * A posted document has moved stock. Rejecting it would claim the correction was refused
     * while the ledger says it happened; a posting is undone by raising another adjustment.
     */
    @Test
    void aPostedAdjustmentCannotBeRejected() {
        StockAdjustment adjustment = draftRaisedBy(DRAFTER);
        adjustment.setStatus("processed");
        adjustment.setProcessedAt(LocalDateTime.now().minusDays(1));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.reject(ADJUSTMENT, "Variance not supported"))
                .withMessageContaining("cannot be rejected");

        assertThat(adjustment.getRejectedAt()).isNull();
    }

    /** One set of rejection columns, so a second rejection would overwrite the first. */
    @Test
    void aRejectedAdjustmentCannotBeRejectedAgain() {
        StockAdjustment adjustment = rejectedAdjustment();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.reject(ADJUSTMENT, "A different reason"))
                .withMessageContaining("cannot be rejected again");

        assertThat(adjustment.getRejectionReason()).isEqualTo("Variance not supported by the count sheet");
    }

    /**
     * Rejection is terminal. Editing the document would have to overwrite the refusal to record
     * whatever came next, and that record is the entire reason to reject rather than delete.
     */
    @Test
    void aRejectedAdjustmentCannotBeEdited() {
        rejectedAdjustment();

        StockAdjustmentCreationDto edit = new StockAdjustmentCreationDto();
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.update(ADJUSTMENT, edit))
                .withMessageContaining("cannot be edited");
    }

    @Test
    void aRejectedAdjustmentCannotBeDeleted() {
        rejectedAdjustment();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.delete(ADJUSTMENT))
                .withMessageContaining("cannot be deleted");
        verify(stockAdjustmentRepository, never()).delete(any(StockAdjustment.class));
    }

    @Test
    void aRejectedAdjustmentCannotThenBeApproved() {
        rejectedAdjustment();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(ADJUSTMENT))
                .withMessageContaining("cannot be approved");

        verifyNoInteractions(inventoryTransactionRepository);
    }

    private StockAdjustment rejectedAdjustment() {
        StockAdjustment adjustment = draftRaisedBy(DRAFTER);
        adjustment.setStatus("rejected");
        adjustment.setRejectedBy(APPROVER);
        adjustment.setRejectedAt(LocalDateTime.now().minusHours(2));
        adjustment.setRejectionReason("Variance not supported by the count sheet");
        return adjustment;
    }
}
