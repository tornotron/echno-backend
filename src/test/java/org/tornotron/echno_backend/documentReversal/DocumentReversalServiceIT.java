package org.tornotron.echno_backend.documentReversal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalDto;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalEligibilityDto;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalRequestDto;
import org.tornotron.echno_backend.documentReversal.enums.DocumentReversalStatus;
import org.tornotron.echno_backend.documentReversal.enums.ReversibleDocumentType;
import org.tornotron.echno_backend.documentReversal.mapper.DocumentReversalMapperImpl;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteRepository;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.grnItem.GrnItemRepository;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.leave.Notification;
import org.tornotron.echno_backend.leave.NotificationRepository;
import org.tornotron.echno_backend.leave.NotificationService;
import org.tornotron.echno_backend.leave.enums.NotificationType;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.payable.PayableRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderService;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransfer.SiteTransferRepository;
import org.tornotron.echno_backend.siteTransfer.SiteTransferService;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
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
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reversal flow against a real CockroachDB, end to end through the service.
 *
 * <p>Runs in the plain {@code @DataJpaTest} context every repository test shares and builds the
 * service by hand from the context's repositories, with the session (user context, role check)
 * mocked. No new Spring context. The ledger rows a document wrote are seeded the way
 * {@code InventoryEventListener} writes them, because that listener runs after commit and a
 * slice test never commits.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DocumentReversalServiceIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired private DocumentReversalRepository reversalRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private SiteTransferRepository siteTransferRepository;
    @Autowired private SiteTransferItemRepository siteTransferItemRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired private GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    @Autowired private GrnItemRepository grnItemRepository;
    @Autowired private PayableRepository payableRepository;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;
    @Autowired private CurrentStockRepository currentStockRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private StorageLocationRepository storageLocationRepository;
    @Autowired private StatusTransitionRepository statusTransitionRepository;
    @Autowired private NotificationRepository notificationRepository;

    private UserContextService userContext;
    private OrganizationSecurityService orgSecurity;
    private InventoryService inventoryService;
    private StatusTransitionRecorder recorder;
    private DocumentReversalService service;

    private Organization org;
    private Organization otherOrg;
    private User creatorUser;
    private User approverUser;
    private User strangerUser;
    private Employee creator;
    private Employee approver;
    private Employee keeperA;
    private Project projectA;
    private Project projectB;
    private StorageLocation storeA1;
    private StorageLocation storeA2;
    private StorageLocation storeB;
    private Material cement;
    private Vendor vendor;

    @BeforeEach
    void seed() {
        userContext = mock(UserContextService.class);
        orgSecurity = mock(OrganizationSecurityService.class);
        inventoryService = new InventoryService(currentStockRepository, inventoryTransactionRepository,
                materialRepository, storageLocationRepository);
        recorder = new StatusTransitionRecorder(statusTransitionRepository);
        NotificationService notifications = new NotificationService(notificationRepository, employeeRepository,
                null, new CurrentEmployeeService(userContext, employeeRepository));
        service = new DocumentReversalService(reversalRepository, new DocumentReversalMapperImpl(),
                new TenantEntityHelper(organizationRepository), userContext,
                new CurrentEmployeeService(userContext, employeeRepository),
                new SelfApprovalPolicy(orgSecurity), new UserNameDirectory(userRepository),
                siteTransferRepository, siteTransferItemRepository, purchaseOrderRepository,
                purchaseOrderItemRepository, goodsReceivedNoteRepository, grnItemRepository, payableRepository,
                inventoryTransactionRepository, inventoryService, statusTransitionRepository, recorder,
                employeeRepository, notifications);

        org = organization("Reversal Org", "reversal@example.test");
        otherOrg = organization("Other Org", "other@example.test");

        creatorUser = user("kc-creator", "Ravi Creator");
        approverUser = user("kc-approver", "Anand Approver");
        strangerUser = user("kc-stranger", "Someone Else");

        projectA = project(org, "Project A");
        projectB = project(org, "Project B");

        creator = employee(org, creatorUser, "Ravi Creator", OrgRole.STORE_KEEPER, projectA);
        approver = employee(org, approverUser, "Anand Approver", OrgRole.PROJECT_MANAGER, projectA);
        keeperA = employee(org, user("kc-keeper-a", "Keeper A"), "Keeper A", OrgRole.STORE_KEEPER, projectA);
        employee(org, strangerUser, "Someone Else", OrgRole.STORE_KEEPER, projectB);

        storeA1 = store(org, projectA, "Store A1");
        storeA2 = store(org, projectA, "Store A2");
        storeB = store(org, projectB, "Store B");

        cement = new Material();
        cement.setMaterialName("Cement");
        cement.setUnit("bag");
        cement.setOrganization(org);
        em.persist(cement);

        vendor = new Vendor();
        vendor.setVendorName("Acme Supplies");
        vendor.setType(VendorType.MATERIALS);
        vendor.setStatus(VendorStatus.ACTIVE);
        vendor.setVendorEmail("acme@example.test");
        vendor.setOrganization(org);
        em.persist(vendor);

        em.flush();
        TenantContext.setCurrentOrgId(org.getId());
        actingAs(creatorUser);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // --- Rule 1: only the creator may ask -----------------------------------------------

    @Test
    void request_byAnyoneButTheCreator_isRefused() {
        SiteTransfer transfer = sameProjectTransfer(10);

        actingAs(strangerUser);
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId())))
                .withMessageContaining("person who raised it");
        assertThat(reversalRepository.count()).isZero();

        actingAs(approverUser);
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId())));
    }

    @Test
    void eligibility_tellsTheCreatorApartFromEverybodyElse() {
        SiteTransfer transfer = sameProjectTransfer(10);

        DocumentReversalEligibilityDto asCreator = service.eligibility(ReversibleDocumentType.SITE_TRANSFER, transfer.getId());
        assertThat(asCreator.reversible()).isTrue();
        assertThat(asCreator.callerIsCreator()).isTrue();
        assertThat(asCreator.blocker()).isNull();

        actingAs(strangerUser);
        assertThat(service.eligibility(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()).callerIsCreator()).isFalse();
    }

    // --- Rule 3: approval restores every store exactly ------------------------------------

    @Test
    void approve_transferBetweenTwoStores_restoresBothBalancesAndLedgerSums() {
        SiteTransfer transfer = sameProjectTransfer(10);
        double a1Before = balance(storeA1), a2Before = balance(storeA2);
        double a1Sum = ledgerSum(storeA1), a2Sum = ledgerSum(storeA2);
        assertThat(a1Before).isEqualTo(40.0);
        assertThat(a2Before).isEqualTo(10.0);

        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()));
        assertThat(pending.getStatus()).isEqualTo(DocumentReversalStatus.PENDING);
        assertThat(pending.getRequestedBy()).isEqualTo(creatorUser.getId());
        assertThat(pending.getRequestedByName()).isEqualTo("Ravi Creator");

        actingAs(approverUser);
        DocumentReversalDto approved = service.approve(pending.getId());
        em.flush();
        em.clear();

        assertThat(approved.getStatus()).isEqualTo(DocumentReversalStatus.APPROVED);
        assertThat(approved.getDecidedBy()).isEqualTo(approverUser.getId());
        assertThat(approved.getReversalReference()).isEqualTo("REV-" + transfer.getTransferNumber());

        // The balances are back where they stood before the transfer, and the ledger agrees.
        assertThat(balance(storeA1)).isEqualTo(50.0);
        assertThat(balance(storeA2)).isEqualTo(0.0);
        assertThat(ledgerSum(storeA1)).isCloseTo(a1Sum + 10.0, within(1e-9));
        assertThat(ledgerSum(storeA2)).isCloseTo(a2Sum - 10.0, within(1e-9));
        List<InventoryTransaction> corrections = inventoryTransactionRepository
                .findByReferenceNumberAndOrganization_IdOrderByIdAsc("REV-" + transfer.getTransferNumber(), org.getId());
        assertThat(corrections).hasSize(2);
        assertThat(corrections).allMatch(row -> row.getTransactionType() == InventoryTransactionType.REVERSAL);
        assertThat(corrections).extracting(InventoryTransaction::getQuantityChanged).containsExactlyInAnyOrder(10.0, -10.0);

        // The document stays, marked reversed and linked both ways.
        SiteTransfer reversed = siteTransferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(reversed.getStatus()).isEqualTo(SiteTransferStatus.REVERSED);
        assertThat(reversed.getReversalId()).isEqualTo(approved.getId());
        assertThat(service.getByDocument(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()))
                .extracting(DocumentReversalDto::getId).containsExactly(approved.getId());
        assertThat(statusTransitionRepository
                .findByEntityTypeAndEntityIdAndOrganization_IdOrderByOccurredAtDescIdDesc(
                        SiteTransferService.HISTORY_ENTITY_TYPE, transfer.getId(), org.getId(),
                        PageRequest.of(0, 5))
                .getContent().get(0).getToStatus()).isEqualTo("REVERSED");

        // Rule 4: the store keeper of the store that has stock to put back is told, as is the requester.
        List<Notification> sent = notificationRepository.findAll();
        assertThat(sent).anyMatch(n -> n.getRecipient().getId().equals(keeperA.getId())
                && n.getNotificationType() == NotificationType.DOCUMENT_REVERSAL_APPROVED
                && n.getMessage().contains("Store A1") && n.getMessage().contains("10 Cement"));
        assertThat(sent).anyMatch(n -> n.getRecipient().getId().equals(creator.getId())
                && n.getNotificationType() == NotificationType.DOCUMENT_REVERSAL_APPROVED);

        // A reversed document cannot be asked about again, even by its creator.
        actingAs(creatorUser);
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId())))
                .withMessageContaining("already been reversed");
    }

    @Test
    void approve_goodsReceipt_restoresTheStoreAndTakesTheReceiptOffTheOrder() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR, 20);
        GoodsReceivedNote grn = receipt(order, storeA1, 20, "15.00");
        recorder.recordSystemChange(PurchaseOrderService.HISTORY_ENTITY_TYPE, order.getId(), org,
                "SENT_TO_VENDOR", "FULLY_RECEIVED", "receipt");
        order.setStatus(PurchaseOrderStatus.FULLY_RECEIVED);
        em.flush();
        assertThat(balance(storeA1)).isEqualTo(70.0);

        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.GOODS_RECEIVED_NOTE, grn.getId()));
        actingAs(approverUser);
        DocumentReversalDto approved = service.approve(pending.getId());
        em.flush();
        em.clear();

        assertThat(balance(storeA1)).isEqualTo(50.0);
        GoodsReceivedNote reversed = goodsReceivedNoteRepository.findById(grn.getId()).orElseThrow();
        assertThat(reversed.getReversalId()).isEqualTo(approved.getId());
        PurchaseOrder after = purchaseOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PurchaseOrderStatus.SENT_TO_VENDOR);
        assertThat(purchaseOrderItemRepository.findByPurchaseOrderId(order.getId()))
                .extracting(PurchaseOrderItem::getReceivedQuantity).containsExactly(0);

        // With the receipt gone, the order itself can now be reversed.
        actingAs(creatorUser);
        assertThat(service.eligibility(ReversibleDocumentType.PURCHASE_ORDER, order.getId()).reversible()).isTrue();
    }

    @Test
    void approve_purchaseOrder_marksItReversedAndMovesNoStock() {
        PurchaseOrder order = order(PurchaseOrderStatus.APPROVED, 20);
        long rowsBefore = inventoryTransactionRepository.count();

        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.PURCHASE_ORDER, order.getId()));
        actingAs(approverUser);
        DocumentReversalDto approved = service.approve(pending.getId());
        em.flush();
        em.clear();

        assertThat(approved.getReversalReference()).isNull();
        assertThat(inventoryTransactionRepository.count()).isEqualTo(rowsBefore);
        PurchaseOrder after = purchaseOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PurchaseOrderStatus.REVERSED);
        assertThat(after.getReversalId()).isEqualTo(approved.getId());
    }

    // --- Rule 2: rejection leaves everything untouched -------------------------------------

    @Test
    void reject_leavesTheDocumentAndTheLedgerUntouched() {
        SiteTransfer transfer = sameProjectTransfer(10);
        long rowsBefore = inventoryTransactionRepository.count();
        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()));

        actingAs(approverUser);
        DocumentReversalDto rejected = service.reject(pending.getId(), "The transfer was right; the count was wrong");
        em.flush();
        em.clear();

        assertThat(rejected.getStatus()).isEqualTo(DocumentReversalStatus.REJECTED);
        assertThat(rejected.getDecisionNote()).isEqualTo("The transfer was right; the count was wrong");
        assertThat(inventoryTransactionRepository.count()).isEqualTo(rowsBefore);
        assertThat(balance(storeA1)).isEqualTo(40.0);
        assertThat(balance(storeA2)).isEqualTo(10.0);
        SiteTransfer untouched = siteTransferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(SiteTransferStatus.COMPLETED);
        assertThat(untouched.getReversalId()).isNull();
        assertThat(notificationRepository.findAll()).anyMatch(n -> n.getRecipient().getId().equals(creator.getId())
                && n.getNotificationType() == NotificationType.DOCUMENT_REVERSAL_REJECTED);

        // Terminal: it cannot be decided again, and the creator may ask afresh.
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(rejected.getId()));
        actingAs(creatorUser);
        assertThat(service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId())).getStatus())
                .isEqualTo(DocumentReversalStatus.PENDING);
    }

    @Test
    void reject_withoutAReason_isRefused() {
        SiteTransfer transfer = sameProjectTransfer(10);
        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()));
        actingAs(approverUser);
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.reject(pending.getId(), "  "));
    }

    @Test
    void approve_byTheRequester_isRefusedWithoutTheBreakGlassRole() {
        SiteTransfer transfer = sameProjectTransfer(10);
        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()));
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(pending.getId()))
                .withMessageContaining("same person");
    }

    @Test
    void request_whileOneIsPending_isRefused() {
        SiteTransfer transfer = sameProjectTransfer(10);
        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId())))
                .withMessageContaining("#" + pending.getId());
        assertThat(service.eligibility(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()).pendingReversalId())
                .isEqualTo(pending.getId());

        // The requester may withdraw it, and only the requester.
        actingAs(strangerUser);
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.cancel(pending.getId()));
        actingAs(creatorUser);
        assertThat(service.cancel(pending.getId()).getStatus()).isEqualTo(DocumentReversalStatus.CANCELLED);
    }

    // --- Rule 5: downstream blockers, named ------------------------------------------------

    @Test
    void request_onAnOrderWithAReceipt_isRefusedNamingTheReceipt() {
        PurchaseOrder order = order(PurchaseOrderStatus.PARTIALLY_RECEIVED, 20);
        GoodsReceivedNote grn = receipt(order, storeA1, 5, "15.00");

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.PURCHASE_ORDER, order.getId())))
                .withMessageContaining(grn.getGrnNumber())
                .withMessageContaining("reverse the receipt first");
        assertThat(service.eligibility(ReversibleDocumentType.PURCHASE_ORDER, order.getId()).blocker())
                .contains(grn.getGrnNumber());
    }

    @Test
    void request_onATransferReceivedAtTheDestination_isRefusedNamingTheDestination() {
        SiteTransfer transfer = crossProjectTransfer(10, 10);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId())))
                .withMessageContaining("received at Project B");
    }

    @Test
    void request_onATransferStillInTransit_isAllowedAndReturnsTheStockToTheSender() {
        SiteTransfer transfer = crossProjectTransfer(10, 0);
        assertThat(balance(storeA1)).isEqualTo(40.0);

        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()));
        actingAs(approverUser);
        service.approve(pending.getId());
        em.flush();
        em.clear();

        assertThat(balance(storeA1)).isEqualTo(50.0);
        assertThat(balance(storeB)).isEqualTo(0.0);
    }

    @Test
    void request_onAReceiptWhoseStockWasIssued_isRefusedNamingTheShortfall() {
        PurchaseOrder order = order(PurchaseOrderStatus.FULLY_RECEIVED, 20);
        GoodsReceivedNote grn = receipt(order, storeA2, 20, "15.00");
        // Issue 15 of the 20 the receipt brought into Store A2.
        ledger(storeA2, -15.0, InventoryTransactionType.USE, "MC-1", null);
        assertThat(balance(storeA2)).isEqualTo(5.0);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.GOODS_RECEIVED_NOTE, grn.getId())))
                .withMessageContaining("Store A2")
                .withMessageContaining("holds only 5")
                .withMessageContaining("15 has been consumed");
    }

    @Test
    void request_onAReceiptWithAPayable_isRefused() {
        PurchaseOrder order = order(PurchaseOrderStatus.FULLY_RECEIVED, 20);
        GoodsReceivedNote grn = receipt(order, storeA1, 20, "15.00");
        Payable payable = new Payable();
        payable.setPayableNumber("PAY-1");
        payable.setContractorName("Acme");
        payable.setProject(projectA);
        payable.setOrganization(org);
        payable.setGoodsReceivedNote(grn);
        payable.setAmountRecorded(new BigDecimal("300.00"));
        payable.setAmountPaid(BigDecimal.ZERO);
        em.persist(payable);
        em.flush();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.GOODS_RECEIVED_NOTE, grn.getId())))
                .withMessageContaining("payable");
    }

    @Test
    void approve_recheckstheBlockerUnderTheLock() {
        SiteTransfer transfer = sameProjectTransfer(10);
        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()));
        // Between the request and the decision, Store A2 issues what the transfer brought in.
        ledger(storeA2, -10.0, InventoryTransactionType.USE, "MC-2", null);

        actingAs(approverUser);
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(pending.getId()))
                .withMessageContaining("consumed since");
        assertThat(reversalRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(DocumentReversalStatus.PENDING);
    }

    // --- Tenant isolation -------------------------------------------------------------------

    @Test
    void anotherTenant_canNeitherSeeNorRequestNorDecide() {
        SiteTransfer transfer = sameProjectTransfer(10);
        DocumentReversalDto pending = service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId()));
        em.flush();

        TenantContext.setCurrentOrgId(otherOrg.getId());
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getById(pending.getId()));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.request(ask(ReversibleDocumentType.SITE_TRANSFER, transfer.getId())));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.approve(pending.getId()));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.reject(pending.getId(), "no"));
        assertThat(service.getAll(0, 10, null).getTotalElements()).isZero();
        assertThat(service.getByDocument(ReversibleDocumentType.SITE_TRANSFER, transfer.getId())).isEmpty();

        TenantContext.setCurrentOrgId(org.getId());
        assertThat(service.getAll(0, 10, DocumentReversalStatus.PENDING).getTotalElements()).isEqualTo(1);
    }

    // --- Fixtures -----------------------------------------------------------------------------

    private void actingAs(User user) {
        when(userContext.getCurrentUserId()).thenReturn(user.getId());
        when(userContext.getCurrentUser()).thenReturn(user);
    }

    private static DocumentReversalRequestDto ask(ReversibleDocumentType type, Long id) {
        return new DocumentReversalRequestDto(type, id, "Raised against the wrong store");
    }

    /**
     * Store A1 opens with 50 bags at 10.00, then a completed transfer of {@code quantity} from
     * A1 to A2 on the same project, with both legs on the ledger as the listener writes them.
     */
    private SiteTransfer sameProjectTransfer(int quantity) {
        ledger(storeA1, 50.0, InventoryTransactionType.OPENING_BALANCE, "OB-1", "10.00");
        SiteTransfer transfer = transfer(projectA, storeA1, projectA, storeA2, SiteTransferStatus.COMPLETED, quantity, quantity);
        ledger(storeA1, -quantity, InventoryTransactionType.TRANSFER_OUT, transfer.getTransferNumber(), "10.00");
        ledger(storeA2, quantity, InventoryTransactionType.TRANSFER_IN, transfer.getTransferNumber(), "10.00");
        em.flush();
        return transfer;
    }

    /** Store A1 opens with 50, then a cross-project transfer to Store B with {@code received} arrived. */
    private SiteTransfer crossProjectTransfer(int sent, int received) {
        ledger(storeA1, 50.0, InventoryTransactionType.OPENING_BALANCE, "OB-1", "10.00");
        SiteTransferStatus status = received == 0 ? SiteTransferStatus.PENDING
                : received < sent ? SiteTransferStatus.PARTIALLY_TRANSFERRED : SiteTransferStatus.COMPLETED;
        SiteTransfer transfer = transfer(projectA, storeA1, projectB, storeB, status, sent, received == 0 ? null : received);
        ledger(storeA1, -sent, InventoryTransactionType.TRANSFER_OUT, transfer.getTransferNumber(), "10.00");
        if (received > 0) {
            ledger(storeB, received, InventoryTransactionType.TRANSFER_IN, transfer.getTransferNumber(), "10.00");
        }
        em.flush();
        return transfer;
    }

    private SiteTransfer transfer(Project from, StorageLocation fromStore, Project to, StorageLocation toStore,
                                  SiteTransferStatus status, int sent, Integer received) {
        SiteTransfer transfer = new SiteTransfer();
        transfer.setTransferNumber("ST-" + System.nanoTime());
        transfer.setIssueDate(LocalDateTime.now());
        transfer.setSendingPerson(creator);
        transfer.setSendingProject(from);
        transfer.setSendingStorageLocation(fromStore);
        transfer.setReceivingProject(to);
        transfer.setReceivingStorageLocation(toStore);
        transfer.setStatus(status);
        transfer.setOrganization(org);
        em.persist(transfer);
        SiteTransferItem line = new SiteTransferItem();
        line.setSiteTransfer(transfer);
        line.setMaterial(cement);
        line.setSentQuantity(sent);
        line.setReceivedQuantity(received);
        line.setOrganization(org);
        em.persist(line);
        recorder.recordCreation(SiteTransferService.HISTORY_ENTITY_TYPE, transfer.getId(), org, status.name(), creatorUser);
        return transfer;
    }

    private PurchaseOrder order(PurchaseOrderStatus status, int quantity) {
        PurchaseOrder order = new PurchaseOrder();
        order.setPoNumber("PO-" + System.nanoTime());
        order.setVendor(vendor);
        order.setStatus(status);
        order.setCreatedBy(creator);
        order.setProject(projectA);
        order.setOrganization(org);
        order.setTotalAmount(new BigDecimal("300.00"));
        em.persist(order);
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setPurchaseOrder(order);
        line.setMaterial(cement);
        line.setOrderedQuantity(quantity);
        line.setReceivedQuantity(0);
        line.setUnitPrice(new BigDecimal("15.00"));
        line.setOrganization(org);
        em.persist(line);
        recorder.recordCreation(PurchaseOrderService.HISTORY_ENTITY_TYPE, order.getId(), org, status.name(), creatorUser);
        em.flush();
        return order;
    }

    /** Store A1 opens with 50, then the receipt lands {@code quantity} at {@code store} against the order. */
    private GoodsReceivedNote receipt(PurchaseOrder order, StorageLocation store, int quantity, String unitCost) {
        ledger(storeA1, 50.0, InventoryTransactionType.OPENING_BALANCE, "OB-1", "10.00");
        GoodsReceivedNote grn = new GoodsReceivedNote();
        grn.setGrnNumber("GRN-" + System.nanoTime());
        grn.setReceivedOn(LocalDateTime.now());
        grn.setReceivedBy(creator);
        grn.setVendor(vendor);
        grn.setPurchaseOrder(order);
        grn.setProject(projectA);
        grn.setStorageLocation(store);
        grn.setOrganization(org);
        em.persist(grn);
        GrnItem line = new GrnItem();
        line.setGoodsReceivedNote(grn);
        line.setMaterial(cement);
        line.setOrderedQuantity(quantity);
        line.setReceivedQuantity(quantity);
        line.setUnitCost(new BigDecimal(unitCost));
        line.setOrganization(org);
        em.persist(line);
        for (PurchaseOrderItem orderLine : purchaseOrderItemRepository.findByPurchaseOrderId(order.getId())) {
            orderLine.setReceivedQuantity(orderLine.getReceivedQuantity() + quantity);
            em.persist(orderLine);
        }
        ledger(store, quantity, InventoryTransactionType.GRN, grn.getGrnNumber(), unitCost);
        em.flush();
        return grn;
    }

    /** Writes one ledger row and moves the balance, the way the listener does. */
    private void ledger(StorageLocation store, double quantity, InventoryTransactionType type, String reference,
                        String unitCost) {
        Project project = store.getProject();
        double opening = inventoryService.findStockAtLocation(cement.getId(), project.getId(), store.getId()).orElse(0.0);
        InventoryTransaction row = new InventoryTransaction();
        row.setTransactionDate(LocalDateTime.now());
        row.setMaterial(cement);
        row.setOpeningStock(opening);
        row.setQuantityChanged(quantity);
        row.setClosingStock(opening + quantity);
        row.setTransactionType(type);
        row.setReferenceNumber(reference);
        row.setRemarks("seed");
        row.setProject(project);
        row.setStorageLocation(store);
        row.setOrganization(org);
        row.setUnitCost(unitCost != null ? new BigDecimal(unitCost) : null);
        inventoryTransactionRepository.save(row);
        inventoryService.updateCurrentStock(cement, project, store, org, quantity,
                quantity > 0 && unitCost != null ? new BigDecimal(unitCost) : null);
    }

    private double balance(StorageLocation store) {
        return inventoryService.findStockAtLocation(cement.getId(), store.getProject().getId(), store.getId()).orElse(0.0);
    }

    private double ledgerSum(StorageLocation store) {
        return em.createQuery("SELECT COALESCE(SUM(t.quantityChanged), 0.0) FROM InventoryTransaction t "
                        + "WHERE t.storageLocation.id = :store AND t.material.id = :material", Double.class)
                .setParameter("store", store.getId()).setParameter("material", cement.getId())
                .getSingleResult();
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
        employee.setEmailAddress(name.toLowerCase().replace(' ', '.') + "@emp.test");
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
