package org.tornotron.echno_backend.purchaseOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
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
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderCreationDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderUpdateDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrder.mapper.PurchaseOrderMapper;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemService;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

/**
 * Orders and their lines ask the access matrix about the order itself, not only about the area: a
 * caller whose grants reach one project sees, raises and changes only that project's orders, and
 * only that project's lines.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;
    private static final Long LINE_ON_MY_PROJECT = 31L;
    private static final Long LINE_ELSEWHERE = 32L;

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private IndentRepository indentRepository;
    @Mock private PurchaseOrderMapper purchaseOrderMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private IndentItemRepository indentItemRepository;
    @Mock private DocumentNumberAllocator documentNumberAllocator;
    @Mock private TransactionRetryTemplate retryTemplate;
    @Mock private StatusTransitionRecorder statusTransitionRecorder;
    @Mock private StatusTransitionRepository statusTransitionRepository;
    @Mock private StatusTransitionMapper statusTransitionMapper;
    @Mock private UserContextService userContextService;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProjectAccess access = new OneProjectAccess();
    private PurchaseOrderService orders;
    private PurchaseOrderItemService lines;
    private Organization organization;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        Employee caller = new Employee();
        caller.setId(7L);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        PurchaseOrderAccess purchaseOrderAccess = new PurchaseOrderAccess(access, currentEmployee);
        orders = new PurchaseOrderService(purchaseOrderRepository, vendorRepository, indentRepository,
                purchaseOrderMapper, tenantEntityHelper, employeeRepository, projectRepository,
                purchaseOrderItemRepository, materialRepository, indentItemRepository,
                documentNumberAllocator, retryTemplate, statusTransitionRecorder,
                statusTransitionRepository, statusTransitionMapper, userContextService, purchaseOrderAccess);
        lines = new PurchaseOrderItemService(purchaseOrderItemRepository, purchaseOrderRepository,
                materialRepository, indentItemRepository, tenantEntityHelper, orders, purchaseOrderAccess);
        lenient().when(retryTemplate.execute(anyString(), any(Predicate.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());
        PurchaseOrder mine = order(ON_MY_PROJECT, MY_PROJECT);
        PurchaseOrder elsewhere = order(ELSEWHERE, OTHER_PROJECT);
        lenient().when(purchaseOrderRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG))
                .thenReturn(Optional.of(mine));
        lenient().when(purchaseOrderRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG))
                .thenReturn(Optional.of(elsewhere));
        lenient().when(purchaseOrderItemRepository.findByIdAndOrganization_Id(LINE_ON_MY_PROJECT, ORG))
                .thenReturn(Optional.of(line(LINE_ON_MY_PROJECT, mine)));
        lenient().when(purchaseOrderItemRepository.findByIdAndOrganization_Id(LINE_ELSEWHERE, ORG))
                .thenReturn(Optional.of(line(LINE_ELSEWHERE, elsewhere)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anOrderTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> orders.getPurchaseOrderById(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> orders.getStatusHistory(ELSEWHERE, 0, 20));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> orders.updatePurchaseOrderStatus(ELSEWHERE, PurchaseOrderStatus.APPROVED));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> lines.getPurchaseOrderItemById(LINE_ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> lines.deletePurchaseOrderItem(LINE_ELSEWHERE));
    }

    @Test
    void anOrderTheCallerMaySeeButNotApproveKeepsItsStatus() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> orders.updatePurchaseOrderStatus(ON_MY_PROJECT, PurchaseOrderStatus.APPROVED));
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void aStatusSentOnAnEditNeedsApproveToo() {
        PurchaseOrderUpdateDto edit = new PurchaseOrderUpdateDto();
        edit.setId(ON_MY_PROJECT);
        edit.setStatus(PurchaseOrderStatus.APPROVED.name());

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> orders.updatePurchaseOrder(edit));
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void anOrderCannotBeMovedOutOfWhatTheCallerMayEdit() {
        Project other = new Project();
        other.setId(OTHER_PROJECT);
        when(projectRepository.findByIdAndOrganization_Id(OTHER_PROJECT, ORG)).thenReturn(Optional.of(other));
        PurchaseOrderUpdateDto edit = new PurchaseOrderUpdateDto();
        edit.setId(ON_MY_PROJECT);
        edit.setProjectId(OTHER_PROJECT);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> orders.updatePurchaseOrder(edit));
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void anOrderCannotBeRaisedOnAProjectTheCallerMayNotRaiseOrdersOn() {
        Vendor vendor = new Vendor();
        vendor.setId(5L);
        Employee buyer = new Employee();
        buyer.setId(7L);
        Project other = new Project();
        other.setId(OTHER_PROJECT);
        when(vendorRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.of(vendor));
        when(employeeRepository.findByIdAndOrganizationId(7L, ORG)).thenReturn(Optional.of(buyer));
        when(projectRepository.findByIdAndOrganization_Id(OTHER_PROJECT, ORG)).thenReturn(Optional.of(other));
        PurchaseOrderCreationDto order = new PurchaseOrderCreationDto();
        order.setVendorId(5L);
        order.setCreatedBy(7L);
        order.setProjectId(OTHER_PROJECT);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> orders.createPurchaseOrder(order));
        verify(documentNumberAllocator, never()).allocate(any(), anyLong());
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void aLineTheCallerMaySeeButNotDeleteIsRefusedAndKept() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> lines.deletePurchaseOrderItem(LINE_ON_MY_PROJECT));
        verify(purchaseOrderItemRepository, never()).deleteByIdAndOrganization_Id(anyLong(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListsAreNarrowedInTheQuery() {
        when(purchaseOrderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(purchaseOrderRepository.findAll(any(Specification.class))).thenReturn(List.of());
        when(purchaseOrderItemRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(purchaseOrderItemRepository.findAll(any(Specification.class))).thenReturn(List.of());

        orders.getAllPurchaseOrders(0, 10);
        orders.getPurchaseOrdersByVendor(5L);
        orders.getPurchaseOrdersByIndent(6L);
        orders.getPurchaseOrdersByStatus(PurchaseOrderStatus.DRAFT);
        lines.getPurchaseOrderItemsPaginated(0, 10);
        lines.getItemsByPurchaseOrderId(ON_MY_PROJECT);
        lines.getItemsByMaterialId(11L);

        verify(purchaseOrderRepository, never()).findAll(any(Pageable.class));
        verify(purchaseOrderRepository, never()).findByVendorId(any());
        verify(purchaseOrderRepository, never()).findByIndentId(any());
        verify(purchaseOrderRepository, never()).findByStatus(any());
        verify(purchaseOrderItemRepository, never()).findAll(any(Pageable.class));
        verify(purchaseOrderItemRepository, never()).findByPurchaseOrderId(any());
        verify(purchaseOrderItemRepository, never()).findByMaterialId(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallerWhoMaySeeEveryOrderGetsTheUnnarrowedLists() {
        access.everything = true;
        when(purchaseOrderRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(purchaseOrderItemRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        orders.getAllPurchaseOrders(0, 10);
        orders.getPurchaseOrdersByVendor(5L);
        lines.getPurchaseOrderItemsPaginated(0, 10);
        lines.getItemsByPurchaseOrderId(ON_MY_PROJECT);

        verify(purchaseOrderRepository).findByVendorId(5L);
        verify(purchaseOrderItemRepository).findByPurchaseOrderId(ON_MY_PROJECT);
        verify(purchaseOrderRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(purchaseOrderRepository, never()).findAll(any(Specification.class));
        verify(purchaseOrderItemRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(purchaseOrderItemRepository, never()).findAll(any(Specification.class));
    }

    @Test
    void aCallerWhoMaySeeNoOrderIsNotQueriedFor() {
        access.nothing = true;

        assertThat(orders.getAllPurchaseOrders(0, 10)).isEmpty();
        assertThat(orders.getPurchaseOrdersByVendor(5L)).isEmpty();
        assertThat(orders.getPurchaseOrdersByIndent(6L)).isEmpty();
        assertThat(orders.getPurchaseOrdersByStatus(PurchaseOrderStatus.DRAFT)).isEmpty();
        assertThat(lines.getPurchaseOrderItemsPaginated(0, 10)).isEmpty();
        assertThat(lines.getItemsByPurchaseOrderId(ON_MY_PROJECT)).isEmpty();
        assertThat(lines.getItemsByMaterialId(11L)).isEmpty();
        verify(purchaseOrderRepository, never()).findAll(any(Pageable.class));
        verify(purchaseOrderRepository, never()).findByVendorId(any());
        verify(purchaseOrderItemRepository, never()).findAll(any(Pageable.class));
        verify(purchaseOrderItemRepository, never()).findByMaterialId(any());
    }

    private PurchaseOrder order(Long id, Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        PurchaseOrder order = new PurchaseOrder();
        order.setId(id);
        order.setPoNumber("PO-" + id);
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setOrganization(organization);
        order.setProject(project);
        return order;
    }

    private PurchaseOrderItem line(Long id, PurchaseOrder order) {
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setId(id);
        line.setPurchaseOrder(order);
        line.setOrganization(organization);
        return line;
    }

    /** Grants read, create and edit on one project's orders, and nothing else. */
    private static final class OneProjectAccess implements AccessService {

        private static final Set<String> HELD =
                Set.of(AccessPermission.READ, AccessPermission.CREATE, AccessPermission.EDIT);

        boolean everything;
        boolean nothing;

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return true;
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return !nothing && HELD.contains(permission.action());
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            if (nothing || !HELD.contains(permission.action())) {
                return AccessFilter.none(subject.organizationId());
            }
            if (everything) {
                return AccessFilter.all(subject.organizationId());
            }
            return new AccessFilter(subject.organizationId(), false, Set.of(MY_PROJECT), null, null);
        }

        @Override
        public AccessGrants grantsFor(AccessSubject subject) {
            return AccessGrants.none();
        }
    }
}
