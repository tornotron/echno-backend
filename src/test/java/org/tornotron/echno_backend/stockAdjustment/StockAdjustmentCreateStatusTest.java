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
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentCreationDto;
import org.tornotron.echno_backend.stockAdjustment.mapper.StockAdjustmentMapper;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A stock adjustment is raised as a draft and reaches its posted state only through
 * {@code approve}, which is what refuses an approval by whoever raised the document, writes
 * the stock-ledger entries and moves the balance. A body that set the status itself would
 * reach that state with none of it: no approver on record, no ledger entries, no movement, and
 * the document reading as dealt with. These pin that the status may only be draft, on the
 * create path and on the update path that writes the same header.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentCreateStatusTest {

    private static final Long ORG = 100L;

    @Mock private StockAdjustmentRepository stockAdjustmentRepository;
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

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new StockAdjustmentService(stockAdjustmentRepository, stockAdjustmentMapper,
                tenantEntityHelper, materialRepository, storageLocationRepository, projectRepository,
                inventoryService, inventoryTransactionRepository, userContextService,
                new SelfApprovalPolicy(orgSecurity));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenAnswer(call -> {
            Organization org = new Organization();
            org.setId(ORG);
            return org;
        });
        lenient().when(stockAdjustmentRepository.saveAndFlush(any(StockAdjustment.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private StockAdjustmentCreationDto dto(String status) {
        StockAdjustmentCreationDto dto = new StockAdjustmentCreationDto();
        dto.setAdjustmentNumber("ADJ-001");
        dto.setType("write_off");
        dto.setJustification("Quarterly count found a shortfall in River Sand");
        dto.setStatus(status);
        return dto;
    }

    @Test
    void create_refusesADocumentRaisedAlreadyApproved() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(dto("APPROVED")))
                .withMessageContaining("cannot be given the status APPROVED");

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_refusesADocumentRaisedInThePostedState() {
        // The state approve() stamps once the movements are on the ledger. Reaching it here
        // would leave a document that reads as posted with nothing posted behind it.
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(dto(StockAdjustmentService.POSTED_STATUS)))
                .withMessageContaining("cannot be given the status processed");

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_withNoStatusInTheBody_raisesADraft() {
        service.create(dto(null));

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StockAdjustmentService.DRAFT_STATUS);
    }

    @Test
    void create_withDraftInTheBody_isAcceptedWhateverItsCase() {
        service.create(dto("Draft"));

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StockAdjustmentService.DRAFT_STATUS);
    }

    @Test
    void update_refusesToMoveADocumentIntoThePostedState() {
        // The document can be edited right up until it is posted, so gating only create would
        // leave the same door one call further along.
        StockAdjustment existing = new StockAdjustment();
        existing.setId(4L);
        existing.setStatus(StockAdjustmentService.DRAFT_STATUS);
        lenient().when(stockAdjustmentRepository.findByIdAndOrganization_Id(4L, ORG))
                .thenReturn(Optional.of(existing));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.update(4L, dto(StockAdjustmentService.POSTED_STATUS)))
                .withMessageContaining("cannot be given the status processed");

        assertThat(existing.getStatus()).isEqualTo(StockAdjustmentService.DRAFT_STATUS);
        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }
}
