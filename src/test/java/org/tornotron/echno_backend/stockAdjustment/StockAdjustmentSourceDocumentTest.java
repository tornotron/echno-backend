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
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransfer.SiteTransferRepository;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentCreationDto;
import org.tornotron.echno_backend.stockAdjustment.enums.StockAdjustmentSourceType;
import org.tornotron.echno_backend.stockAdjustment.mapper.StockAdjustmentMapper;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserNameDirectory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stock adjustment can name the document it was raised to answer, and the first such document
 * is a site transfer whose receipt left an open variance.
 *
 * <p>The reference is what the whole feature rests on. A prefilled adjustment form that dropped
 * it on submit would leave a correction nobody can trace back to what caused it, which is worse
 * than offering no route at all, so these pin that what the caller sends is what is stored and
 * what is read back.
 *
 * <p>The other half of what they pin is that the id is not taken on trust. The column carries no
 * foreign key, so nothing in the database stops a caller naming a transfer in somebody else's
 * organization; the write path has to, and the reverse read has to scope its match the same way.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentSourceDocumentTest {

    private static final Long ORG = 100L;
    private static final Long TRANSFER = 31L;

    @Mock private StockAdjustmentRepository stockAdjustmentRepository;
    @Mock private UserNameDirectory userNameDirectory;
    @Mock private SiteTransferRepository siteTransferRepository;
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
                new SelfApprovalPolicy(orgSecurity), userNameDirectory,
                siteTransferRepository);
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

    private StockAdjustmentCreationDto dto() {
        StockAdjustmentCreationDto dto = new StockAdjustmentCreationDto();
        dto.setAdjustmentNumber("ADJ-001");
        dto.setType("write_off");
        dto.setJustification("Two bags did not arrive with transfer TRF-0031");
        return dto;
    }

    private SiteTransfer transfer(SiteTransferStatus status) {
        SiteTransfer transfer = new SiteTransfer();
        transfer.setId(TRANSFER);
        transfer.setStatus(status);
        return transfer;
    }

    private StockAdjustment saved() {
        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    /**
     * The one this whole piece of work exists for: the reference the caller sent is on the
     * document that is written. Without it the prefilled form posts a transfer id into nothing.
     */
    @Test
    void create_withATransferReference_writesItOntoTheDocument() {
        when(siteTransferRepository.findByIdAndOrganization_Id(TRANSFER, ORG))
                .thenReturn(Optional.of(transfer(SiteTransferStatus.COMPLETED)));

        StockAdjustmentCreationDto dto = dto();
        dto.setSourceDocumentType(StockAdjustmentSourceType.SITE_TRANSFER);
        dto.setSourceDocumentId(TRANSFER);

        service.create(dto);

        StockAdjustment written = saved();
        assertThat(written.getSourceDocumentType()).isEqualTo(StockAdjustmentSourceType.SITE_TRANSFER);
        assertThat(written.getSourceDocumentId()).isEqualTo(TRANSFER);
    }

    /**
     * A transfer that is still being received is a live cause too: the variance on a partially
     * received transfer is the case the two-step document was built for.
     */
    @Test
    void create_againstAPartiallyReceivedTransfer_isAllowed() {
        when(siteTransferRepository.findByIdAndOrganization_Id(TRANSFER, ORG))
                .thenReturn(Optional.of(transfer(SiteTransferStatus.PARTIALLY_TRANSFERRED)));

        StockAdjustmentCreationDto dto = dto();
        dto.setSourceDocumentType(StockAdjustmentSourceType.SITE_TRANSFER);
        dto.setSourceDocumentId(TRANSFER);

        service.create(dto);

        assertThat(saved().getSourceDocumentId()).isEqualTo(TRANSFER);
    }

    /** An adjustment that answers no document stores neither half, which is the ordinary case. */
    @Test
    void create_withNoReference_storesNeitherHalf() {
        service.create(dto());

        StockAdjustment written = saved();
        assertThat(written.getSourceDocumentType()).isNull();
        assertThat(written.getSourceDocumentId()).isNull();
    }

    /**
     * The cross-tenant guard. The column holds no foreign key, so a caller naming a transfer that
     * belongs to somebody else would otherwise be stored as a working pointer into another
     * tenant's data, and every reader of the adjustment would follow it.
     */
    @Test
    void create_namingATransferOutsideTheCallersOrganization_isRefused() {
        when(siteTransferRepository.findByIdAndOrganization_Id(TRANSFER, ORG))
                .thenReturn(Optional.empty());

        StockAdjustmentCreationDto dto = dto();
        dto.setSourceDocumentType(StockAdjustmentSourceType.SITE_TRANSFER);
        dto.setSourceDocumentId(TRANSFER);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.create(dto))
                .withMessageContaining("was not found in this organization");

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    /**
     * The id the caller sent is the id that is checked. A guard that looked the document up by
     * one key while the value written came from another is the defect this codebase keeps
     * finding, so this pins that the lookup uses the caller's own id and the caller's own
     * organization.
     */
    @Test
    void create_checksTheSameTransferIdItStores() {
        when(siteTransferRepository.findByIdAndOrganization_Id(TRANSFER, ORG))
                .thenReturn(Optional.of(transfer(SiteTransferStatus.COMPLETED)));

        StockAdjustmentCreationDto dto = dto();
        dto.setSourceDocumentType(StockAdjustmentSourceType.SITE_TRANSFER);
        dto.setSourceDocumentId(TRANSFER);

        service.create(dto);

        verify(siteTransferRepository).findByIdAndOrganization_Id(TRANSFER, ORG);
        assertThat(saved().getSourceDocumentId()).isEqualTo(TRANSFER);
    }

    /**
     * Cancelling is reachable only from PENDING and returns the whole sent quantity to the
     * sending site, so a cancelled transfer left no variance to close. Its lines still read as
     * in transit, which is the trap: that figure is history, not a live shortage.
     */
    @Test
    void create_namingACancelledTransfer_isRefused() {
        when(siteTransferRepository.findByIdAndOrganization_Id(TRANSFER, ORG))
                .thenReturn(Optional.of(transfer(SiteTransferStatus.CANCELLED)));

        StockAdjustmentCreationDto dto = dto();
        dto.setSourceDocumentType(StockAdjustmentSourceType.SITE_TRANSFER);
        dto.setSourceDocumentId(TRANSFER);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(dto))
                .withMessageContaining("left no variance for an adjustment to close");

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    /** An id with nothing saying what kind of document it is cannot be followed. */
    @Test
    void create_withAnIdAndNoType_isRefused() {
        StockAdjustmentCreationDto dto = dto();
        dto.setSourceDocumentId(TRANSFER);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(dto))
                .withMessageContaining("without saying what kind of document it is");

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    /** A type naming no document is a claim to a reference that is not there. */
    @Test
    void create_withATypeAndNoId_isRefused() {
        StockAdjustmentCreationDto dto = dto();
        dto.setSourceDocumentType(StockAdjustmentSourceType.SITE_TRANSFER);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(dto))
                .withMessageContaining("without naming which one");

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    /**
     * The edit path writes the same header, so it takes the same checks. Without this an
     * adjustment could be raised clean and then edited onto a transfer belonging to somebody
     * else, which is the create guard with an extra step.
     */
    @Test
    void update_namingATransferOutsideTheCallersOrganization_isRefused() {
        StockAdjustment existing = new StockAdjustment();
        existing.setId(9L);
        Organization org = new Organization();
        org.setId(ORG);
        existing.setOrganization(org);
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(9L, ORG))
                .thenReturn(Optional.of(existing));
        when(siteTransferRepository.findByIdAndOrganization_Id(TRANSFER, ORG))
                .thenReturn(Optional.empty());

        StockAdjustmentCreationDto dto = dto();
        dto.setSourceDocumentType(StockAdjustmentSourceType.SITE_TRANSFER);
        dto.setSourceDocumentId(TRANSFER);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.update(9L, dto))
                .withMessageContaining("was not found in this organization");

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    /**
     * The reverse read is anchored on an id the caller supplied against a column with no foreign
     * key behind it. Matching on the type and id alone would hand back whichever tenant's
     * adjustment happened to name the same number, so the organization is part of the query.
     */
    @Test
    void getBySourceDocument_matchesWithinTheCallersOrganizationOnly() {
        when(stockAdjustmentRepository
                .findBySourceDocumentTypeAndSourceDocumentIdAndOrganization_IdOrderByCreatedAtDesc(
                        StockAdjustmentSourceType.SITE_TRANSFER, TRANSFER, ORG))
                .thenReturn(List.of());

        assertThat(service.getBySourceDocument(StockAdjustmentSourceType.SITE_TRANSFER, TRANSFER))
                .isEmpty();

        verify(stockAdjustmentRepository)
                .findBySourceDocumentTypeAndSourceDocumentIdAndOrganization_IdOrderByCreatedAtDesc(
                        StockAdjustmentSourceType.SITE_TRANSFER, TRANSFER, ORG);
    }

    /** What the transfer screen reads to know its variance has been answered. */
    @Test
    void getBySourceDocument_returnsTheAdjustmentsNamingTheDocument() {
        StockAdjustment closing = new StockAdjustment();
        closing.setId(14L);
        closing.setSourceDocumentType(StockAdjustmentSourceType.SITE_TRANSFER);
        closing.setSourceDocumentId(TRANSFER);
        when(stockAdjustmentRepository
                .findBySourceDocumentTypeAndSourceDocumentIdAndOrganization_IdOrderByCreatedAtDesc(
                        StockAdjustmentSourceType.SITE_TRANSFER, TRANSFER, ORG))
                .thenReturn(List.of(closing));
        when(stockAdjustmentMapper.toDto(any(StockAdjustment.class), any()))
                .thenAnswer(call -> {
                    var dto = new org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentDto();
                    StockAdjustment source = call.getArgument(0);
                    dto.setId(source.getId());
                    dto.setSourceDocumentType(source.getSourceDocumentType());
                    dto.setSourceDocumentId(source.getSourceDocumentId());
                    return dto;
                });

        var found = service.getBySourceDocument(StockAdjustmentSourceType.SITE_TRANSFER, TRANSFER);

        assertThat(found).singleElement().satisfies(dto -> {
            assertThat(dto.getId()).isEqualTo(14L);
            assertThat(dto.getSourceDocumentId()).isEqualTo(TRANSFER);
            assertThat(dto.getSourceDocumentType())
                    .isEqualTo(StockAdjustmentSourceType.SITE_TRANSFER);
        });
    }
}
