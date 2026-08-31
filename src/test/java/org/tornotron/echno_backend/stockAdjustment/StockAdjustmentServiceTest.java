package org.tornotron.echno_backend.stockAdjustment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
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
 * Unit tests for the drafting half of StockAdjustmentService: an id that names nothing in
 * the current tenant is rejected, a null id resolves to no association rather than an
 * error, line items are wired to their parent and stamped with the organization, and an
 * update replaces the whole line-item collection, the opening balance a line is raised
 * against is read from the stock rather than taken from the request body, and the user who
 * raised the document is taken from the session rather than from the request body. The repositories, mapper, and
 * tenant helper are mocked; assertions read the entity captured on saveAndFlush. Posting to
 * the stock ledger is covered by {@link StockAdjustmentApprovalTest}.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentServiceTest {

    private static final Long ORG = 100L;
    private static final Long MATERIAL = 11L;
    private static final Long LOCATION = 3L;
    private static final Long PROJECT = 9L;
    private static final Long SESSION_USER = 77L;

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
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private StockAdjustmentLineItemCreationDto line(Long materialId, Long locationId) {
        StockAdjustmentLineItemCreationDto l = new StockAdjustmentLineItemCreationDto();
        l.setMaterialId(materialId);
        l.setLocationId(locationId);
        l.setDescription("line");
        return l;
    }

    private StockAdjustmentCreationDto baseDto() {
        StockAdjustmentCreationDto dto = new StockAdjustmentCreationDto();
        dto.setAdjustmentNumber("ADJ-001");
        dto.setType("write_off");
        dto.setStatus("draft");
        return dto;
    }

    private void stubReferenceLookups() {
        Material material = new Material();
        material.setId(MATERIAL);
        StorageLocation location = new StorageLocation();
        location.setId(LOCATION);
        Project project = new Project();
        project.setId(PROJECT);
        lenient().when(materialRepository.findByIdAndOrganization_Id(MATERIAL, ORG)).thenReturn(Optional.of(material));
        lenient().when(storageLocationRepository.findByIdAndOrganization_Id(LOCATION, ORG)).thenReturn(Optional.of(location));
        lenient().when(projectRepository.findByIdAndOrganization_Id(PROJECT, ORG)).thenReturn(Optional.of(project));
    }

    @Test
    void create_resolvesReferencesAndWiresLineItems() {
        stubReferenceLookups();
        StockAdjustmentCreationDto dto = baseDto();
        dto.setLocationId(LOCATION);
        dto.setProjectId(PROJECT);
        dto.setLineItems(List.of(line(MATERIAL, LOCATION)));

        service.create(dto);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        StockAdjustment saved = captor.getValue();
        assertThat(saved.getOrganization().getId()).isEqualTo(ORG);
        assertThat(saved.getLocation().getId()).isEqualTo(LOCATION);
        assertThat(saved.getProject().getId()).isEqualTo(PROJECT);
        assertThat(saved.getLineItems()).hasSize(1);
        StockAdjustmentLineItem item = saved.getLineItems().get(0);
        assertThat(item.getMaterial().getId()).isEqualTo(MATERIAL);
        assertThat(item.getOrganization().getId()).isEqualTo(ORG);
        assertThat(item.getStockAdjustment()).isSameAs(saved);
    }

    @Test
    void create_nullHeaderReferences_leaveAssociationsNull() {
        StockAdjustmentCreationDto dto = baseDto();     // no locationId / projectId / lineItems

        service.create(dto);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        StockAdjustment saved = captor.getValue();
        assertThat(saved.getLocation()).isNull();
        assertThat(saved.getProject()).isNull();
        assertThat(saved.getLineItems()).isEmpty();
    }

    @Test
    void create_stampsTheOpeningBalanceRatherThanTrustingTheFigureSent() {
        stubReferenceLookups();
        when(inventoryService.findStockAtLocation(MATERIAL, PROJECT, LOCATION))
                .thenReturn(Optional.of(400.0));
        StockAdjustmentCreationDto dto = baseDto();
        dto.setProjectId(PROJECT);
        StockAdjustmentLineItemCreationDto line = line(MATERIAL, LOCATION);
        // On the web form this is a box somebody types into, defaulting to zero and checked
        // against nothing. It is the figure the approval is now measured against, so the server
        // reads it rather than taking it on trust.
        line.setSystemQuantity(999.0);
        dto.setLineItems(List.of(line));

        service.create(dto);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getLineItems().get(0).getSystemQuantity()).isEqualTo(400.0);
    }

    @Test
    void create_recomputesTheVarianceFromTheStampedOpeningBalance() {
        stubReferenceLookups();
        when(inventoryService.findStockAtLocation(MATERIAL, PROJECT, LOCATION))
                .thenReturn(Optional.of(400.0));
        StockAdjustmentCreationDto dto = baseDto();
        dto.setProjectId(PROJECT);
        StockAdjustmentLineItemCreationDto line = line(MATERIAL, LOCATION);
        line.setSystemQuantity(500.0);
        line.setPhysicalQuantity(370.0);
        line.setAdjustmentQuantity(-130.0);
        dto.setLineItems(List.of(line));

        service.create(dto);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        StockAdjustmentLineItem saved = captor.getValue().getLineItems().get(0);
        // The three figures on a line are one piece of arithmetic; a document showing 400, a count
        // of 370 and a difference of -130 says nothing anyone can act on.
        assertThat(saved.getSystemQuantity()).isEqualTo(400.0);
        assertThat(saved.getPhysicalQuantity()).isEqualTo(370.0);
        assertThat(saved.getAdjustmentQuantity()).isEqualTo(-30.0);
    }

    @Test
    void create_lineWithNoMaterial_keepsTheFigureSent() {
        StockAdjustmentCreationDto dto = baseDto();
        StockAdjustmentLineItemCreationDto line = line(null, null);
        line.setSystemQuantity(500.0);
        dto.setLineItems(List.of(line));

        service.create(dto);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        // There is no balance to read for a line that names no material, and such a half-filled
        // line cannot be approved anyway, so nothing is gained by throwing the number away.
        assertThat(captor.getValue().getLineItems().get(0).getSystemQuantity()).isEqualTo(500.0);
    }

    @Test
    void update_restampsTheOpeningBalance() {
        stubReferenceLookups();
        when(inventoryService.findUnlocatedStock(MATERIAL, PROJECT)).thenReturn(Optional.of(370.0));
        StockAdjustment existing = new StockAdjustment();
        Organization org = new Organization();
        org.setId(ORG);
        existing.setOrganization(org);
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(existing));

        StockAdjustmentCreationDto dto = baseDto();
        dto.setProjectId(PROJECT);
        StockAdjustmentLineItemCreationDto line = line(MATERIAL, null);
        line.setSystemQuantity(400.0);
        dto.setLineItems(List.of(line));

        service.update(5L, dto);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        // Saving the document again is the way back from an approval refused because the balance
        // moved, so an edit has to take up the current figure rather than leave the stale one.
        assertThat(captor.getValue().getLineItems().get(0).getSystemQuantity()).isEqualTo(370.0);
    }

    @Test
    void create_unknownLineMaterial_throwsNotFound() {
        when(materialRepository.findByIdAndOrganization_Id(MATERIAL, ORG)).thenReturn(Optional.empty());
        StockAdjustmentCreationDto dto = baseDto();
        dto.setLineItems(List.of(line(MATERIAL, null)));

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.create(dto));

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_unknownDocument_throwsNotFound() {
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.update(5L, baseDto()));

        verify(stockAdjustmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_replacesLineItemCollection() {
        stubReferenceLookups();
        StockAdjustment existing = new StockAdjustment();
        Organization org = new Organization();
        org.setId(ORG);
        existing.setOrganization(org);
        StockAdjustmentLineItem stale = new StockAdjustmentLineItem();
        existing.addLineItem(stale);
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(existing));

        StockAdjustmentCreationDto dto = baseDto();
        dto.setLineItems(List.of(line(MATERIAL, null)));

        service.update(5L, dto);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        StockAdjustment saved = captor.getValue();
        assertThat(saved.getLineItems()).hasSize(1);
        assertThat(saved.getLineItems()).doesNotContain(stale);
        assertThat(saved.getLineItems().get(0).getMaterial().getId()).isEqualTo(MATERIAL);
    }

    @Test
    void delete_unknownDocument_throwsNotFound() {
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.delete(5L));

        verify(stockAdjustmentRepository, never()).delete(any());
    }

    @Test
    void delete_existingDocument_deletes() {
        StockAdjustment existing = new StockAdjustment();
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(existing));

        service.delete(5L);

        verify(stockAdjustmentRepository).delete(existing);
    }

    @Test
    void create_recordsTheSessionUserAsTheRaiserAndIgnoresTheOneInTheBody() {
        stubReferenceLookups();
        when(userContextService.getCurrentUserId()).thenReturn(SESSION_USER);
        StockAdjustmentCreationDto dto = baseDto();
        // A caller naming somebody else as the raiser would otherwise clear their own way to
        // approve the document, since approval is checked against this field.
        dto.setSubmittedBy(999L);

        service.create(dto);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(stockAdjustmentRepository).saveAndFlush(captor.capture());
        StockAdjustment saved = captor.getValue();
        assertThat(saved.getSubmittedBy()).isEqualTo(SESSION_USER);
        assertThat(saved.getSubmittedAt()).isNotNull();
    }

    @Test
    void update_leavesTheRecordedRaiserAloneWhateverTheBodySays() {
        stubReferenceLookups();
        StockAdjustment existing = new StockAdjustment();
        existing.setId(4L);
        existing.setSubmittedBy(SESSION_USER);
        when(stockAdjustmentRepository.lockByIdAndOrganizationId(4L, ORG))
                .thenReturn(Optional.of(existing));
        StockAdjustmentCreationDto dto = baseDto();
        dto.setSubmittedBy(999L);

        service.update(4L, dto);

        assertThat(existing.getSubmittedBy()).isEqualTo(SESSION_USER);
    }
}
