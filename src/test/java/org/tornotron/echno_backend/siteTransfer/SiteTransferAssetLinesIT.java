package org.tornotron.echno_backend.siteTransfer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.asset.Asset;
import org.tornotron.echno_backend.asset.AssetMovement;
import org.tornotron.echno_backend.asset.AssetMovementRepository;
import org.tornotron.echno_backend.asset.AssetMovementType;
import org.tornotron.echno_backend.asset.AssetRepository;
import org.tornotron.echno_backend.asset.AssetService;
import org.tornotron.echno_backend.asset.dto.AssetCreationDto;
import org.tornotron.echno_backend.asset.dto.AssetDto;
import org.tornotron.echno_backend.asset.dto.AssetMovementCreationDto;
import org.tornotron.echno_backend.asset.mapper.AssetMapper;
import org.tornotron.echno_backend.asset.mapper.AssetMovementMapper;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.events.SiteTransferReceivedEvent;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.documentReversal.DocumentReversalRepository;
import org.tornotron.echno_backend.documentReversal.DocumentReversalService;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalDto;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalRequestDto;
import org.tornotron.echno_backend.documentReversal.enums.ReversibleDocumentType;
import org.tornotron.echno_backend.documentReversal.mapper.DocumentReversalMapperImpl;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteRepository;
import org.tornotron.echno_backend.grnItem.GrnItemRepository;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.leave.NotificationRepository;
import org.tornotron.echno_backend.leave.NotificationService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.payable.PayableRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferAssetOptionDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCancellationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferReceiptDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferReceiptLineDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferLineType;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransfer.mapper.SiteTransferMapper;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserNameDirectory;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An asset travelling on a site transfer, end to end against a real CockroachDB.
 *
 * <p>Runs in the plain {@code @DataJpaTest} context the repository tests share and builds the
 * transfer, asset and reversal services by hand from its repositories, with the session mocked,
 * as {@code DocumentReversalServiceIT} does. No new Spring context. The stock listener runs after
 * commit and a slice test never commits, so material legs are not posted here; the asset side
 * runs inside the transfer's own transaction and is what these tests read.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SiteTransferAssetLinesIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private StorageLocationRepository storageLocationRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private VendorRepository vendorRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private AssetMovementRepository assetMovementRepository;
    @Autowired private SiteTransferRepository siteTransferRepository;
    @Autowired private SiteTransferItemRepository siteTransferItemRepository;
    @Autowired private CurrentStockRepository currentStockRepository;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;
    @Autowired private StatusTransitionRepository statusTransitionRepository;
    @Autowired private DocumentReversalRepository reversalRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired private GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    @Autowired private GrnItemRepository grnItemRepository;
    @Autowired private PayableRepository payableRepository;
    @Autowired private NotificationRepository notificationRepository;

    private UserContextService userContext;
    private ApplicationEventPublisher events;
    private InventoryService inventoryService;
    private AssetService assetService;
    private SiteTransferService transfers;
    private DocumentReversalService reversals;

    private Organization org;
    private Organization otherOrg;
    private User keeperUser;
    private User approverUser;
    private Employee keeper;
    private Project projectA;
    private Project projectB;
    private Project otherOrgProject;
    private StorageLocation storeA1;
    private StorageLocation storeA2;
    private StorageLocation storeB;
    private StorageLocation otherOrgStore;
    private Material cement;

    private final AtomicInteger numbers = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void seed() {
        userContext = mock(UserContextService.class);
        events = mock(ApplicationEventPublisher.class);
        TenantEntityHelper tenantEntityHelper = new TenantEntityHelper(organizationRepository);
        inventoryService = new InventoryService(currentStockRepository, inventoryTransactionRepository,
                materialRepository, storageLocationRepository);
        StatusTransitionRecorder recorder = new StatusTransitionRecorder(statusTransitionRepository);
        CurrentEmployeeService currentEmployee = new CurrentEmployeeService(userContext, employeeRepository);

        assetService = new AssetService(assetRepository, Mappers.getMapper(AssetMapper.class), tenantEntityHelper,
                vendorRepository, storageLocationRepository, projectRepository, assetMovementRepository,
                Mappers.getMapper(AssetMovementMapper.class), userContext, mock(AttachmentService.class),
                siteTransferItemRepository);
        SiteTransferAssetLines assetLines = new SiteTransferAssetLines(assetRepository, assetService,
                assetMovementRepository, siteTransferItemRepository);

        DocumentNumberAllocator allocator = mock(DocumentNumberAllocator.class);
        lenient().when(allocator.allocate(eq(DocumentNumberType.SITE_TRANSFER), any()))
                .thenAnswer(inv -> "TRF-IT-" + System.nanoTime() + "-" + numbers.incrementAndGet());
        TransactionRetryTemplate retry = mock(TransactionRetryTemplate.class);
        lenient().when(retry.execute(anyString(), any(Supplier.class)))
                .thenAnswer(inv -> inv.getArgument(1, Supplier.class).get());
        lenient().when(retry.execute(anyString(), any(Predicate.class), any(Supplier.class)))
                .thenAnswer(inv -> inv.getArgument(2, Supplier.class).get());

        SiteTransferMapper mapper = Mappers.getMapper(SiteTransferMapper.class);
        ReflectionTestUtils.setField(mapper, "employeeMapper", mock(EmployeeMapper.class));

        transfers = new SiteTransferService(siteTransferRepository, siteTransferItemRepository, userRepository,
                materialRepository, inventoryService, events, mapper, tenantEntityHelper, employeeRepository,
                projectRepository, storageLocationRepository, allocator, retry,
                new SiteTransferReceiptReconciler(recorder), currentEmployee, userContext, recorder,
                statusTransitionRepository, mock(StatusTransitionMapper.class), assetLines);

        OrganizationSecurityService orgSecurity = mock(OrganizationSecurityService.class);
        NotificationService notifications = new NotificationService(notificationRepository, employeeRepository,
                null, currentEmployee);
        reversals = new DocumentReversalService(reversalRepository, new DocumentReversalMapperImpl(),
                tenantEntityHelper, userContext, currentEmployee, new SelfApprovalPolicy(orgSecurity),
                new UserNameDirectory(userRepository), siteTransferRepository, siteTransferItemRepository,
                purchaseOrderRepository, purchaseOrderItemRepository, goodsReceivedNoteRepository, grnItemRepository,
                payableRepository, inventoryTransactionRepository, inventoryService, statusTransitionRepository,
                recorder, employeeRepository, notifications, assetLines);

        org = organization("Asset Transfer Org", "asset-transfer@example.test");
        otherOrg = organization("Other Asset Org", "other-asset@example.test");
        projectA = project(org, "Central Yard");
        projectB = project(org, "Silver Oak Residency");
        otherOrgProject = project(otherOrg, "Somebody Else's Site");
        keeperUser = user("kc-keeper", "Kavya Keeper");
        approverUser = user("kc-approver", "Arun Approver");
        keeper = employee(org, keeperUser, "Kavya Keeper", OrgRole.STORE_KEEPER, projectA);
        employee(org, approverUser, "Arun Approver", OrgRole.PROJECT_MANAGER, projectA);
        storeA1 = store(org, projectA, "Yard Store 1");
        storeA2 = store(org, projectA, "Yard Store 2");
        storeB = store(org, projectB, "Silver Oak Store");
        otherOrgStore = store(otherOrg, otherOrgProject, "Foreign Store");

        cement = new Material();
        cement.setMaterialName("Cement");
        cement.setUnit("bag");
        cement.setOrganization(org);
        em.persist(cement);
        em.flush();

        TenantContext.setCurrentOrgId(org.getId());
        actingAs(keeperUser);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // --- Moving between projects: in transit, then received -----------------------------------

    @Test
    void anAssetSentToAnotherProjectIsInTransitUntilReceivedThenMovesWithALedgerEntry() {
        Asset excavator = asset("AST-001", "Excavator", projectA, storeA1);

        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
        flushAndClear();

        assertThat(created.getStatus()).isEqualTo(SiteTransferStatus.PENDING);
        SiteTransferItem line = onlyLine(created.getId());
        assertThat(line.getLineType()).isEqualTo(SiteTransferLineType.ASSET);
        assertThat(line.getSentQuantity()).isEqualTo(1);
        assertThat(line.getMaterial()).isNull();
        assertThat(line.isAssetInTransit()).isTrue();

        // Still at the sending site on the ledger, and marked in transit on the register.
        assertThat(placement(excavator)).containsExactly(projectA.getId(), storeA1.getId());
        AssetDto onRegister = assetService.getAssetById(excavator.getId());
        assertThat(onRegister.getInTransitSiteTransferId()).isEqualTo(created.getId());
        assertThat(onRegister.getInTransitSiteTransferNumber()).isEqualTo(created.getTransferNumber());

        transfers.receiveSiteTransfer(created.getId(), receipt(line.getId(), 1));
        flushAndClear();

        assertThat(siteTransferRepository.findById(created.getId()).orElseThrow().getStatus())
                .isEqualTo(SiteTransferStatus.COMPLETED);
        assertThat(onlyLine(created.getId()).isAssetInTransit()).isFalse();
        assertThat(placement(excavator)).containsExactly(projectB.getId(), storeB.getId());
        assertThat(assetService.getAssetById(excavator.getId()).getInTransitSiteTransferId()).isNull();

        AssetMovement moved = latestMovement(excavator);
        assertThat(moved.getMovementType()).isEqualTo(AssetMovementType.TRANSFER);
        assertThat(moved.getSiteTransferId()).isEqualTo(created.getId());
        assertThat(moved.getReferenceNumber()).isEqualTo(created.getTransferNumber());
        assertThat(moved.getFromProjectName()).isEqualTo("Central Yard");
        assertThat(moved.getToProjectName()).isEqualTo("Silver Oak Residency");
        assertThat(moved.getReason()).contains(created.getTransferNumber()).contains("Kavya Keeper");

        // No stock movement is published for an asset line.
        verify(events, never())
                .publishEvent(any(SiteTransferReceivedEvent.class));
    }

    @Test
    void anAssetRecordedAsNotYetArrivedStaysInTransit() {
        Asset excavator = asset("AST-002", "Excavator", projectA, storeA1);
        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
        flushAndClear();
        SiteTransferItem line = onlyLine(created.getId());

        transfers.receiveSiteTransfer(created.getId(), receipt(line.getId(), 0));
        flushAndClear();

        assertThat(onlyLine(created.getId()).isAssetInTransit()).isTrue();
        assertThat(placement(excavator)).containsExactly(projectA.getId(), storeA1.getId());
    }

    @Test
    void anAssetLineCannotBeOverReceivedEvenWithTheAcknowledgement() {
        Asset excavator = asset("AST-003", "Excavator", projectA, storeA1);
        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
        flushAndClear();
        SiteTransferItem line = onlyLine(created.getId());

        SiteTransferReceiptDto twice = receipt(line.getId(), 2);
        twice.setAllowOverReceipt(true);
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> transfers.receiveSiteTransfer(created.getId(), twice))
                .withMessageContaining("AST-003");
    }

    // --- Within one project: complete at once --------------------------------------------------

    @Test
    void aStoreToStoreTransferMovesTheAssetAtOnce() {
        Asset mixer = asset("AST-010", "Concrete Mixer", projectA, storeA1);

        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectA, storeA2, assetLine(mixer)));
        flushAndClear();

        assertThat(created.getStatus()).isEqualTo(SiteTransferStatus.COMPLETED);
        SiteTransferItem line = onlyLine(created.getId());
        assertThat(line.isAssetInTransit()).isFalse();
        assertThat(line.getReceivedQuantity()).isEqualTo(1);
        assertThat(placement(mixer)).containsExactly(projectA.getId(), storeA2.getId());
        assertThat(latestMovement(mixer).getSiteTransferId()).isEqualTo(created.getId());
    }

    @Test
    void materialAndAssetLinesTravelTogether() {
        seedCement(storeA1, 40);
        Asset mixer = asset("AST-011", "Concrete Mixer", projectA, storeA1);
        SiteTransferItemDto cementLine = new SiteTransferItemDto();
        cementLine.setMaterialId(cement.getId());
        cementLine.setSentQuantity(10);

        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, cementLine, assetLine(mixer)));
        flushAndClear();

        List<SiteTransferItem> lines = siteTransferItemRepository.findBySiteTransferId(created.getId());
        assertThat(lines).extracting(SiteTransferItem::getLineType)
                .containsExactlyInAnyOrder(SiteTransferLineType.MATERIAL, SiteTransferLineType.ASSET);

        List<SiteTransferReceiptLineDto> both = new ArrayList<>();
        for (SiteTransferItem line : lines) {
            SiteTransferReceiptLineDto answer = new SiteTransferReceiptLineDto();
            answer.setItemId(line.getId());
            answer.setReceivedQuantity(line.isAssetLine() ? 1 : 10);
            both.add(answer);
        }
        SiteTransferReceiptDto receipt = new SiteTransferReceiptDto();
        receipt.setItems(both);
        transfers.receiveSiteTransfer(created.getId(), receipt);
        flushAndClear();

        assertThat(siteTransferRepository.findById(created.getId()).orElseThrow().getStatus())
                .isEqualTo(SiteTransferStatus.COMPLETED);
        assertThat(placement(mixer)).containsExactly(projectB.getId(), storeB.getId());
        // The stock leg is still published for the material line, and only for it.
        verify(events).publishEvent(ArgumentMatchers.<ApplicationEvent>argThat(
                event -> event instanceof SiteTransferReceivedEvent received
                        && received.getReceivedLines().size() == 1
                        && !received.getReceivedLines().get(0).item().isAssetLine()));
    }

    // --- The invariants ------------------------------------------------------------------------

    @Test
    void anAssetCanBeOnOnlyOneOpenTransfer() {
        Asset excavator = asset("AST-020", "Excavator", projectA, storeA1);
        SiteTransferDto first = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
        flushAndClear();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> transfers.createSiteTransfer(
                        creation(projectA, storeA1, projectB, storeB, assetLine(excavator))))
                .withMessageContaining("already in transit")
                .withMessageContaining(first.getTransferNumber());
    }

    @Test
    void theSchemaItselfRefusesASecondInTransitLineForOneAsset() {
        Asset excavator = asset("AST-021", "Excavator", projectA, storeA1);
        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
        flushAndClear();

        SiteTransferItem duplicate = new SiteTransferItem();
        duplicate.setSiteTransfer(em.find(SiteTransfer.class, created.getId()));
        duplicate.setLineType(SiteTransferLineType.ASSET);
        duplicate.setAsset(em.find(Asset.class, excavator.getId()));
        duplicate.setSentQuantity(1);
        duplicate.setAssetInTransit(true);
        duplicate.setOrganization(em.find(Organization.class, org.getId()));
        assertThatThrownBy(() -> {
            em.persist(duplicate);
            em.flush();
        }).hasStackTraceContaining("uk_site_transfer_item_asset_in_transit");
    }

    @Test
    void anAssetMustBeAtTheSendingStoreToBeSent() {
        Asset elsewhere = asset("AST-030", "Generator", projectA, storeA2);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> transfers.createSiteTransfer(
                        creation(projectA, storeA1, projectB, storeB, assetLine(elsewhere))))
                .withMessageContaining("Yard Store 2")
                .withMessageContaining("not at Central Yard, Yard Store 1");
        assertThat(siteTransferRepository.findBySendingProjectId(projectA.getId())).isEmpty();
    }

    @Test
    void anAssetLineAlwaysSendsOneUnitAndNamesTheAssetOnce() {
        Asset excavator = asset("AST-031", "Excavator", projectA, storeA1);
        SiteTransferItemDto two = assetLine(excavator);
        two.setSentQuantity(2);
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> transfers.createSiteTransfer(creation(projectA, storeA1, projectB, storeB, two)))
                .withMessageContaining("one unit");

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> transfers.createSiteTransfer(creation(projectA, storeA1, projectB, storeB,
                        assetLine(excavator), assetLine(excavator))))
                .withMessageContaining("more than one line");

        SiteTransferItemDto both = assetLine(excavator);
        both.setMaterialId(cement.getId());
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> transfers.createSiteTransfer(creation(projectA, storeA1, projectB, storeB, both)))
                .withMessageContaining("cannot also name a material");
    }

    @Test
    void anAssetInTransitCannotBeMovedByHand() {
        Asset excavator = asset("AST-040", "Excavator", projectA, storeA1);
        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
        flushAndClear();

        AssetMovementCreationDto byHand = new AssetMovementCreationDto();
        byHand.setToProjectId(projectA.getId());
        byHand.setToLocationId(storeA2.getId());
        byHand.setReason("Moved across the yard");
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> assetService.recordMovement(excavator.getId(), byHand))
                .withMessageContaining("in transit on site transfer " + created.getTransferNumber());
    }

    @Test
    void sendableAssetsAreThoseAtTheStoreAndNotInTransit() {
        Asset free = asset("AST-050", "Compactor", projectA, storeA1);
        Asset travelling = asset("AST-051", "Excavator", projectA, storeA1);
        asset("AST-052", "Generator", projectA, storeA2);
        Asset noStore = asset("AST-053", "Welding Set", projectA, null);
        transfers.createSiteTransfer(creation(projectA, storeA1, projectB, storeB, assetLine(travelling)));
        flushAndClear();

        assertThat(transfers.getSendableAssets(projectA.getId(), storeA1.getId()).getContent())
                .extracting(SiteTransferAssetOptionDto::getId).containsExactly(free.getId());
        assertThat(transfers.getSendableAssets(projectA.getId(), null).getContent())
                .extracting(SiteTransferAssetOptionDto::getAssetCode).containsExactly(noStore.getAssetId());
    }

    // --- Cancellation and reversal -------------------------------------------------------------

    @Test
    void cancellingATransferInTransitReleasesTheAssetWhereItWas() {
        Asset excavator = asset("AST-060", "Excavator", projectA, storeA1);
        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
        flushAndClear();
        long entriesBefore = entries(excavator);

        SiteTransferCancellationDto cancel = new SiteTransferCancellationDto();
        cancel.setReason("Lorry never came");
        transfers.cancelSiteTransfer(created.getId(), cancel);
        flushAndClear();

        assertThat(onlyLine(created.getId()).isAssetInTransit()).isFalse();
        assertThat(placement(excavator)).containsExactly(projectA.getId(), storeA1.getId());
        assertThat(entries(excavator)).isEqualTo(entriesBefore);
        // Free to go again.
        transfers.createSiteTransfer(creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
    }

    @Test
    void reversingAStoreToStoreTransferPutsTheAssetBackWithACorrection() {
        Asset mixer = asset("AST-070", "Concrete Mixer", projectA, storeA1);
        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectA, storeA2, assetLine(mixer)));
        flushAndClear();
        AssetMovement transferEntry = latestMovement(mixer);

        DocumentReversalDto pending = reversals.request(ask(created.getId()));
        actingAs(approverUser);
        reversals.approve(pending.getId());
        flushAndClear();

        assertThat(siteTransferRepository.findById(created.getId()).orElseThrow().getStatus())
                .isEqualTo(SiteTransferStatus.REVERSED);
        assertThat(placement(mixer)).containsExactly(projectA.getId(), storeA1.getId());
        AssetMovement correction = latestMovement(mixer);
        assertThat(correction.getMovementType()).isEqualTo(AssetMovementType.CORRECTION);
        assertThat(correction.getCorrectsMovementId()).isEqualTo(transferEntry.getId());
        assertThat(correction.getSiteTransferId()).isEqualTo(created.getId());
    }

    @Test
    void reversingATransferInTransitReleasesTheAsset() {
        Asset excavator = asset("AST-071", "Excavator", projectA, storeA1);
        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectB, storeB, assetLine(excavator)));
        flushAndClear();

        DocumentReversalDto pending = reversals.request(ask(created.getId()));
        actingAs(approverUser);
        reversals.approve(pending.getId());
        flushAndClear();

        assertThat(onlyLine(created.getId()).isAssetInTransit()).isFalse();
        assertThat(placement(excavator)).containsExactly(projectA.getId(), storeA1.getId());
        assertThat(assetService.getAssetById(excavator.getId()).getInTransitSiteTransferId()).isNull();
    }

    @Test
    void aTransferCannotBeReversedOnceItsAssetHasMovedOn() {
        Asset mixer = asset("AST-072", "Concrete Mixer", projectA, storeA1);
        SiteTransferDto created = transfers.createSiteTransfer(
                creation(projectA, storeA1, projectA, storeA2, assetLine(mixer)));
        flushAndClear();

        AssetMovementCreationDto onward = new AssetMovementCreationDto();
        onward.setToProjectId(projectB.getId());
        onward.setToLocationId(storeB.getId());
        onward.setReason("Sent on to Silver Oak");
        assetService.recordMovement(mixer.getId(), onward);
        flushAndClear();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> reversals.request(ask(created.getId())))
                .withMessageContaining("moved on since");
    }

    // --- Tenant isolation ----------------------------------------------------------------------

    @Test
    void anotherOrganizationsAssetCannotBeSentOrListed() {
        TenantContext.setCurrentOrgId(otherOrg.getId());
        Asset foreign = asset("AST-900", "Foreign Crane", otherOrgProject, otherOrgStore);
        TenantContext.setCurrentOrgId(org.getId());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> transfers.createSiteTransfer(
                        creation(projectA, storeA1, projectB, storeB, assetLine(foreign))));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> transfers.getSendableAssets(otherOrgProject.getId(), otherOrgStore.getId()));
    }

    // --- helpers -------------------------------------------------------------------------------

    private SiteTransferCreationDto creation(Project from, StorageLocation fromStore, Project to,
                                             StorageLocation toStore, SiteTransferItemDto... lines) {
        SiteTransferCreationDto dto = new SiteTransferCreationDto();
        dto.setIssueDate(LocalDateTime.now().minusMinutes(1));
        dto.setSendingPerson(keeper.getId());
        dto.setSendingProjectId(from.getId());
        dto.setSendingStorageLocationId(fromStore != null ? fromStore.getId() : null);
        dto.setReceivingProjectId(to.getId());
        dto.setReceivingStorageLocationId(toStore != null ? toStore.getId() : null);
        dto.setItems(List.of(lines));
        return dto;
    }

    private static SiteTransferItemDto assetLine(Asset asset) {
        SiteTransferItemDto line = new SiteTransferItemDto();
        line.setLineType(SiteTransferLineType.ASSET);
        line.setAssetId(asset.getId());
        line.setSentQuantity(1);
        return line;
    }

    private static SiteTransferReceiptDto receipt(Long itemId, int quantity) {
        SiteTransferReceiptLineDto line = new SiteTransferReceiptLineDto();
        line.setItemId(itemId);
        line.setReceivedQuantity(quantity);
        SiteTransferReceiptDto dto = new SiteTransferReceiptDto();
        dto.setItems(List.of(line));
        return dto;
    }

    private static DocumentReversalRequestDto ask(Long transferId) {
        return new DocumentReversalRequestDto(ReversibleDocumentType.SITE_TRANSFER, transferId,
                "Raised against the wrong store");
    }

    private Asset asset(String code, String name, Project project, StorageLocation store) {
        AssetCreationDto dto = new AssetCreationDto();
        dto.setAssetId(code);
        dto.setName(name);
        dto.setStatus("available");
        dto.setType("heavy-equipment");
        dto.setAssignedProjectId(project.getId());
        dto.setLocationId(store != null ? store.getId() : null);
        dto.setMovedAt(LocalDateTime.now().minusHours(1));
        AssetDto created = assetService.createAsset(dto);
        em.flush();
        return assetRepository.findById(created.getId()).orElseThrow();
    }

    private List<Long> placement(Asset asset) {
        Asset read = assetRepository.findById(asset.getId()).orElseThrow();
        List<Long> where = new ArrayList<>();
        where.add(read.getAssignedProject() != null ? read.getAssignedProject().getId() : null);
        where.add(read.getLocation() != null ? read.getLocation().getId() : null);
        return where;
    }

    private AssetMovement latestMovement(Asset asset) {
        return assetMovementRepository
                .findFirstByAsset_IdAndOrganization_IdOrderByMovedAtDescIdDesc(asset.getId(), org.getId())
                .orElseThrow();
    }

    private long entries(Asset asset) {
        return assetMovementRepository.countByAsset_IdAndOrganization_Id(asset.getId(), org.getId());
    }

    private SiteTransferItem onlyLine(Long transferId) {
        List<SiteTransferItem> lines = siteTransferItemRepository.findBySiteTransferId(transferId);
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }

    private void seedCement(StorageLocation store, double quantity) {
        InventoryTransaction row = new InventoryTransaction();
        row.setTransactionDate(LocalDateTime.now());
        row.setMaterial(cement);
        row.setOpeningStock(0.0);
        row.setQuantityChanged(quantity);
        row.setClosingStock(quantity);
        row.setTransactionType(InventoryTransactionType.OPENING_BALANCE);
        row.setReferenceNumber("OB-" + System.nanoTime());
        row.setRemarks("seed");
        row.setProject(store.getProject());
        row.setStorageLocation(store);
        row.setOrganization(org);
        row.setUnitCost(new BigDecimal("10.00"));
        inventoryTransactionRepository.save(row);
        inventoryService.updateCurrentStock(cement, store.getProject(), store, org, quantity, new BigDecimal("10.00"));
        em.flush();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private void actingAs(User user) {
        when(userContext.getCurrentUserId()).thenReturn(user.getId());
        when(userContext.getCurrentUser()).thenReturn(user);
    }

    private Organization organization(String name, String email) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress("addr");
        organization.setOrganizationEmail(email);
        organization.setOrganizationPhone("0000000000");
        em.persist(organization);
        return organization;
    }

    private User user(String keycloakId, String name) {
        User user = new User();
        user.setKeycloakId(keycloakId + "-" + System.nanoTime());
        user.setName(name);
        em.persist(user);
        return user;
    }

    private Project project(Organization owner, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(owner);
        em.persist(project);
        return project;
    }

    private Employee employee(Organization owner, User user, String name, OrgRole role, Project project) {
        Employee employee = new Employee();
        employee.setOrganization(owner);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase().replace(' ', '.') + System.nanoTime() + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.getOrgRoles().add(role);
        em.persist(employee);
        project.getEmployees().add(employee);
        em.persist(project);
        return employee;
    }

    private StorageLocation store(Organization owner, Project project, String name) {
        StorageLocation store = new StorageLocation();
        store.setLocationName(name);
        store.setLocationType(StorageLocationType.PROJECT_SITE);
        store.setProject(project);
        store.setOrganization(owner);
        em.persist(store);
        return store;
    }
}
