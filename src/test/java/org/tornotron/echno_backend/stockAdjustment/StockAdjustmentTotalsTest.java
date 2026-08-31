package org.tornotron.echno_backend.stockAdjustment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentCreationDto;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentLineItemCreationDto;
import org.tornotron.echno_backend.stockAdjustment.mapper.StockAdjustmentMapper;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserNameDirectory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The arithmetic a stock-adjustment draft states about itself.
 *
 * <p>A line's variance is stamped from the balance the document is raised against, so a header
 * total the client computed is a sum of figures the server has since replaced, and a document
 * could show a header variance that did not match the sum of its own lines. Nothing posts from
 * these fields, so this is a document that misreports itself rather than a wrong stock figure,
 * but the argument that made the line's opening balance the server's applies unchanged: a number
 * the server accepts and never checks is one somebody eventually relies on.
 *
 * <p>So all three derived figures are computed here rather than guarded. Guarding would have to
 * refuse a document whose header disagreed with its lines, which fires on a client that rounded
 * differently rather than on anything worth stopping, and a draft is exactly where a person is
 * still allowed to be halfway through.
 *
 * <p>Posting is covered by {@link StockAdjustmentApprovalTest}, which restates both totals from
 * what the approval actually wrote.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentTotalsTest {

    private static final Long ORG = 100L;
    private static final Long MATERIAL = 11L;
    private static final Long OTHER_MATERIAL = 12L;
    private static final Long LOCATION = 3L;
    private static final Long PROJECT = 9L;

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

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new StockAdjustmentService(stockAdjustmentRepository, stockAdjustmentMapper,
                tenantEntityHelper, materialRepository, storageLocationRepository, projectRepository,
                inventoryService, inventoryTransactionRepository, userContextService,
                new SelfApprovalPolicy(orgSecurity), userNameDirectory);
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenAnswer(inv -> {
            Organization org = new Organization();
            org.setId(ORG);
            return org;
        });
        lenient().when(stockAdjustmentRepository.saveAndFlush(any(StockAdjustment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Material material = new Material();
        material.setId(MATERIAL);
        Material other = new Material();
        other.setId(OTHER_MATERIAL);
        StorageLocation location = new StorageLocation();
        location.setId(LOCATION);
        Project project = new Project();
        project.setId(PROJECT);
        lenient().when(materialRepository.findByIdAndOrganization_Id(MATERIAL, ORG))
                .thenReturn(Optional.of(material));
        lenient().when(materialRepository.findByIdAndOrganization_Id(OTHER_MATERIAL, ORG))
                .thenReturn(Optional.of(other));
        lenient().when(storageLocationRepository.findByIdAndOrganization_Id(LOCATION, ORG))
                .thenReturn(Optional.of(location));
        lenient().when(projectRepository.findByIdAndOrganization_Id(PROJECT, ORG))
                .thenReturn(Optional.of(project));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A document naming the project and location every line is counted at. */
    private StockAdjustmentCreationDto baseDto() {
        StockAdjustmentCreationDto dto = new StockAdjustmentCreationDto();
        dto.setAdjustmentNumber("SA-2026-0021");
        dto.setType("physical_count");
        dto.setStatus("draft");
        dto.setProjectId(PROJECT);
        dto.setLocationId(LOCATION);
        return dto;
    }

    private StockAdjustmentLineItemCreationDto countedLine(Long materialId, double counted, String unitValue) {
        StockAdjustmentLineItemCreationDto line = new StockAdjustmentLineItemCreationDto();
        line.setMaterialId(materialId);
        line.setPhysicalQuantity(counted);
        line.setReason("physical_count");
        if (unitValue != null) {
            line.setUnitValue(new BigDecimal(unitValue));
        }
        return line;
    }

    private void balance(Long materialId, double quantity) {
        lenient().when(inventoryService.findStockAtLocation(materialId, PROJECT, LOCATION))
                .thenReturn(Optional.of(quantity));
    }

    private StockAdjustment saved() {
        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void aLineValueIsRecomputedFromTheStampedVarianceAndTheUnitValue() {
        // 480 on the shelf, 460 counted, so the line is short 20 bags at 380 apiece. The client
        // sent a value of its own, computed from a system quantity it typed rather than read.
        balance(MATERIAL, 480.0);
        StockAdjustmentCreationDto dto = baseDto();
        StockAdjustmentLineItemCreationDto line = countedLine(MATERIAL, 460.0, "380.00");
        line.setSystemQuantity(500.0);
        line.setTotalAdjustmentValue(new BigDecimal("-15200.00"));
        dto.setLineItems(List.of(line));

        service.create(dto);

        StockAdjustmentLineItem stamped = saved().getLineItems().get(0);
        assertThat(stamped.getSystemQuantity()).isEqualTo(480.0);
        assertThat(stamped.getAdjustmentQuantity()).isEqualTo(-20.0);
        assertThat(stamped.getTotalAdjustmentValue()).isEqualByComparingTo("-7600.00");
    }

    @Test
    void aLineWithNoUnitValueKeepsTheValueItWasSentWith() {
        // Nothing to multiply by, so the sent figure is better than none. The same reasoning
        // stampOpeningBalance uses for a line whose balance cannot be read; approving such a line
        // writes its value from the running average cost it posts at.
        balance(MATERIAL, 480.0);
        StockAdjustmentCreationDto dto = baseDto();
        StockAdjustmentLineItemCreationDto line = countedLine(MATERIAL, 460.0, null);
        line.setTotalAdjustmentValue(new BigDecimal("-7600.00"));
        dto.setLineItems(List.of(line));

        service.create(dto);

        assertThat(saved().getLineItems().get(0).getTotalAdjustmentValue())
                .isEqualByComparingTo("-7600.00");
    }

    @Test
    void theHeaderTotalsAreSummedFromTheLinesAndNotTakenFromTheBody() {
        balance(MATERIAL, 480.0);
        balance(OTHER_MATERIAL, 100.0);
        StockAdjustmentCreationDto dto = baseDto();
        // A header the client computed from the figures it sent, which the server has replaced.
        dto.setTotalVarianceQuantity(-40.0);
        dto.setTotalAdjustmentValue(new BigDecimal("-15200.00"));
        dto.setLineItems(List.of(
                countedLine(MATERIAL, 460.0, "380.00"),        // -20 at 380 = -7600
                countedLine(OTHER_MATERIAL, 105.0, "50.00")));  //  +5 at  50 =  +250
        dto.getLineItems().forEach(line -> line.setLocationId(LOCATION));

        service.create(dto);

        StockAdjustment document = saved();
        assertThat(document.getTotalVarianceQuantity()).isEqualTo(-15.0);
        assertThat(document.getTotalAdjustmentValue()).isEqualByComparingTo("-7350.00");
    }

    @Test
    void aDocumentWithNoLinesTotalsZeroRatherThanWhateverWasSent() {
        StockAdjustmentCreationDto dto = baseDto();
        dto.setTotalVarianceQuantity(-40.0);
        dto.setTotalAdjustmentValue(new BigDecimal("-15200.00"));

        service.create(dto);

        StockAdjustment document = saved();
        assertThat(document.getTotalVarianceQuantity()).isEqualTo(0.0);
        assertThat(document.getTotalAdjustmentValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void anEditRestatesTheHeaderTotalsFromTheLinesItLeavesBehind() {
        // The stale totals belong to the lines the edit is about to remove, so keeping them would
        // leave a document describing a count it no longer carries.
        balance(MATERIAL, 480.0);
        StockAdjustment existing = new StockAdjustment();
        existing.setId(4L);
        Organization org = new Organization();
        org.setId(ORG);
        existing.setOrganization(org);
        existing.setTotalVarianceQuantity(-40.0);
        existing.setTotalAdjustmentValue(new BigDecimal("-15200.00"));
        existing.addLineItem(new StockAdjustmentLineItem());
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(4L, ORG))
                .thenReturn(Optional.of(existing));

        StockAdjustmentCreationDto dto = baseDto();
        dto.setTotalVarianceQuantity(-40.0);
        dto.setTotalAdjustmentValue(new BigDecimal("-15200.00"));
        StockAdjustmentLineItemCreationDto line = countedLine(MATERIAL, 470.0, "380.00");
        line.setLocationId(LOCATION);
        dto.setLineItems(List.of(line));

        service.update(4L, dto);

        assertThat(existing.getTotalVarianceQuantity()).isEqualTo(-10.0);
        assertThat(existing.getTotalAdjustmentValue()).isEqualByComparingTo("-3800.00");
    }
}
