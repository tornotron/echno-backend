package org.tornotron.echno_backend.stockAdjustment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentDto;
import org.tornotron.echno_backend.stockAdjustment.mapper.StockAdjustmentMapper;
import org.tornotron.echno_backend.stockAdjustment.mapper.StockAdjustmentMapperImpl;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserDisplayName;
import org.tornotron.echno_backend.user.UserNameDirectory;
import org.tornotron.echno_backend.user.UserNameLookup;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins that a stock adjustment comes back naming the people who acted on it, and that a page
 * costs one directory read however many rows it has.
 *
 * <p>The stamps hold user ids. The web app used to resolve them through the employee lookup,
 * which is keyed by employee id and carries no user id, so it missed for most ids and, where an
 * employee id happened to equal a user id, put a different person's name against an approval.
 * echno-web#346 and #349 replaced that with {@code User #<id>}, honest but useless.
 *
 * <p>The real mapper is used here rather than a mock, because what is being checked is that the
 * conversion reads the names out of the lookup it was handed instead of asking for them itself:
 * a lookup inside the conversion would cost a round trip per stamp per row and the call site
 * would show nothing, which is the shape {@code MapperDatabaseAccessTest} forbids.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentStampNameTest {

    private static final Long ORG = 100L;
    private static final Long SUBMITTER = 41L;
    private static final Long APPROVER = 42L;
    private static final Long DELETED_USER = 43L;

    @Mock private StockAdjustmentRepository stockAdjustmentRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private MaterialRepository materialRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private InventoryService inventoryService;
    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private UserContextService userContextService;
    @Mock private OrganizationSecurityService orgSecurity;
    @Mock private UserNameDirectory userNameDirectory;

    private final StockAdjustmentMapper mapper = new StockAdjustmentMapperImpl();

    private StockAdjustmentService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new StockAdjustmentService(stockAdjustmentRepository, mapper,
                tenantEntityHelper, materialRepository, storageLocationRepository, projectRepository,
                inventoryService, inventoryTransactionRepository, userContextService,
                new SelfApprovalPolicy(orgSecurity), userNameDirectory);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void readingOneAdjustment_namesTheSubmitterAndTheApprover() {
        when(stockAdjustmentRepository.findByIdAndOrganization_Id(1L, ORG))
                .thenReturn(Optional.of(adjustment(1L, SUBMITTER, APPROVER)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(SUBMITTER, "Anand Rajashekar", "anand@echno.test"),
                new UserDisplayName(APPROVER, "Aneesh Johny", "aneesh@echno.test"))));

        StockAdjustmentDto dto = service.getById(1L);

        assertThat(dto.getSubmittedByName()).isEqualTo("Anand Rajashekar");
        assertThat(dto.getApprovedByName()).isEqualTo("Aneesh Johny");
        // approvedBy also stamps processedBy on approval, so the same name lands on both.
        assertThat(dto.getProcessedByName()).isEqualTo("Aneesh Johny");
        assertThat(dto.getRejectedByName()).as("a document that was never rejected has no rejector")
                .isNull();
    }

    @Test
    void anApproverWhoseAccountIsGone_stillRendersOnTheDocument() {
        when(stockAdjustmentRepository.findByIdAndOrganization_Id(1L, ORG))
                .thenReturn(Optional.of(adjustment(1L, SUBMITTER, DELETED_USER)));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(SUBMITTER, "Anand Rajashekar", "anand@echno.test"))));

        StockAdjustmentDto dto = service.getById(1L);

        assertThat(dto.getApprovedBy()).isEqualTo(DELETED_USER);
        assertThat(dto.getApprovedByName()).isEqualTo("User #" + DELETED_USER);
    }

    @Test
    void listingAPage_readsTheDirectoryOnceForEveryStampOnIt() {
        List<StockAdjustment> page = List.of(
                adjustment(1L, SUBMITTER, APPROVER),
                adjustment(2L, SUBMITTER, APPROVER),
                adjustment(3L, DELETED_USER, APPROVER));
        when(stockAdjustmentRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(page));
        when(userNameDirectory.namesFor(anyCollection())).thenReturn(UserNameLookup.of(List.of(
                new UserDisplayName(SUBMITTER, "Anand Rajashekar", "anand@echno.test"),
                new UserDisplayName(APPROVER, "Aneesh Johny", "aneesh@echno.test"))));

        List<StockAdjustmentDto> dtos = service.getAll(0, 20).getContent();

        ArgumentCaptor<Collection<Long>> asked = ArgumentCaptor.captor();
        verify(userNameDirectory, times(1)).namesFor(asked.capture());
        assertThat(asked.getValue())
                .as("every stamp on the page goes in one request, ids repeated across rows included")
                .contains(SUBMITTER, APPROVER, DELETED_USER);
        assertThat(dtos).extracting(StockAdjustmentDto::getApprovedByName)
                .containsExactly("Aneesh Johny", "Aneesh Johny", "Aneesh Johny");
        assertThat(dtos.get(2).getSubmittedByName()).isEqualTo("User #" + DELETED_USER);
    }

    private StockAdjustment adjustment(Long id, Long submittedBy, Long approvedBy) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setId(id);
        adjustment.setSubmittedBy(submittedBy);
        adjustment.setApprovedBy(approvedBy);
        adjustment.setProcessedBy(approvedBy);
        return adjustment;
    }
}
