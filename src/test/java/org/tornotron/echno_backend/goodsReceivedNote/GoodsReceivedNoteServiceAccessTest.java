package org.tornotron.echno_backend.goodsReceivedNote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteCreationDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteUpdateDto;
import org.tornotron.echno_backend.goodsReceivedNote.mapper.GoodsReceivedNoteMapper;
import org.tornotron.echno_backend.grnItem.GrnItemRepository;
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
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderReceiptReconciler;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

/**
 * Receipts ask the access matrix about the receipt itself, not only about the area: a caller whose
 * grants reach one project sees, records and corrects only that project's receipts.
 */
@ExtendWith(MockitoExtension.class)
class GoodsReceivedNoteServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;

    @Mock private GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    @Mock private GrnItemRepository grnItemRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private UserRepository userRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private GoodsReceivedNoteMapper goodsReceivedNoteMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private DocumentNumberAllocator documentNumberAllocator;
    @Mock private TransactionRetryTemplate retryTemplate;
    @Mock private PurchaseOrderReceiptReconciler purchaseOrderReceiptReconciler;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProjectAccess access = new OneProjectAccess();
    private GoodsReceivedNoteService receipts;
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
        receipts = new GoodsReceivedNoteService(goodsReceivedNoteRepository, grnItemRepository,
                vendorRepository, userRepository, materialRepository, eventPublisher,
                goodsReceivedNoteMapper, tenantEntityHelper, employeeRepository, projectRepository,
                storageLocationRepository, purchaseOrderRepository, documentNumberAllocator, retryTemplate,
                purchaseOrderReceiptReconciler, new GoodsReceivedNoteAccess(access, currentEmployee));
        lenient().when(retryTemplate.execute(anyString(), any(Predicate.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());
        lenient().when(goodsReceivedNoteRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG))
                .thenReturn(Optional.of(receipt(ON_MY_PROJECT, MY_PROJECT)));
        lenient().when(goodsReceivedNoteRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG))
                .thenReturn(Optional.of(receipt(ELSEWHERE, OTHER_PROJECT)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aReceiptTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> receipts.getGrnById(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> receipts.updateGoodsReceivedNote(edit(ELSEWHERE)));
        verify(goodsReceivedNoteRepository, never()).save(any());
    }

    @Test
    void aReceiptTheCallerMaySeeButNotEditIsRefusedAndKept() {
        access.mayEdit = false;

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> receipts.updateGoodsReceivedNote(edit(ON_MY_PROJECT)));
        verify(goodsReceivedNoteRepository, never()).save(any());
    }

    @Test
    void aReceiptCannotBeRecordedOnAProjectTheCallerMayNotRecordReceiptsOn() {
        Vendor vendor = new Vendor();
        vendor.setId(5L);
        Employee receiver = new Employee();
        receiver.setId(7L);
        Project other = new Project();
        other.setId(OTHER_PROJECT);
        when(vendorRepository.findByIdAndOrganization_Id(5L, ORG)).thenReturn(Optional.of(vendor));
        when(employeeRepository.findByIdAndOrganizationId(7L, ORG)).thenReturn(Optional.of(receiver));
        when(projectRepository.findByIdAndOrganization_Id(OTHER_PROJECT, ORG)).thenReturn(Optional.of(other));
        GoodsReceivedNoteCreationDto receipt = new GoodsReceivedNoteCreationDto();
        receipt.setVendorId(5L);
        receipt.setReceivedByEmployeeId(7L);
        receipt.setProjectId(OTHER_PROJECT);
        receipt.setPurchaseOrderId(9L);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> receipts.createGoodsReceivedNote(receipt));
        verify(documentNumberAllocator, never()).allocate(any(), anyLong());
        verify(goodsReceivedNoteRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListsAreNarrowedInTheQuery() {
        when(goodsReceivedNoteRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(goodsReceivedNoteRepository.findAll(any(Specification.class))).thenReturn(List.of());
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 0, 0);

        receipts.getAllGrns(0, 10);
        receipts.getGrnsByVendor(5L);
        receipts.getGrnsByVendor(5L, 0, 10);
        receipts.getGrnsByDateRange(from, to);
        receipts.getGrnsByDateRange(from, to, 0, 10);

        verify(goodsReceivedNoteRepository, never()).findAll(any(Pageable.class));
        verify(goodsReceivedNoteRepository, never()).findByVendorId(any());
        verify(goodsReceivedNoteRepository, never()).findByVendorId(any(), any());
        verify(goodsReceivedNoteRepository, never()).findByReceivedOnBetween(any(), any());
        verify(goodsReceivedNoteRepository, never()).findByReceivedOnBetween(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallerWhoMaySeeEveryReceiptGetsTheUnnarrowedLists() {
        access.everything = true;
        when(goodsReceivedNoteRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        receipts.getAllGrns(0, 10);
        receipts.getGrnsByVendor(5L);

        verify(goodsReceivedNoteRepository).findByVendorId(5L);
        verify(goodsReceivedNoteRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(goodsReceivedNoteRepository, never()).findAll(any(Specification.class));
    }

    @Test
    void aCallerWhoMaySeeNoReceiptIsNotQueriedFor() {
        access.nothing = true;
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 0, 0);

        assertThat(receipts.getAllGrns(0, 10)).isEmpty();
        assertThat(receipts.getGrnsByVendor(5L)).isEmpty();
        assertThat(receipts.getGrnsByVendor(5L, 0, 10)).isEmpty();
        assertThat(receipts.getGrnsByDateRange(from, to)).isEmpty();
        assertThat(receipts.getGrnsByDateRange(from, to, 0, 10)).isEmpty();
        verify(goodsReceivedNoteRepository, never()).findAll(any(Pageable.class));
        verify(goodsReceivedNoteRepository, never()).findByVendorId(any());
        verify(goodsReceivedNoteRepository, never()).findByReceivedOnBetween(any(), any());
    }

    private static GoodsReceivedNoteUpdateDto edit(Long id) {
        GoodsReceivedNoteUpdateDto edit = new GoodsReceivedNoteUpdateDto();
        edit.setId(id);
        edit.setInvoiceNumber("INV-1");
        return edit;
    }

    private GoodsReceivedNote receipt(Long id, Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        GoodsReceivedNote receipt = new GoodsReceivedNote();
        receipt.setId(id);
        receipt.setGrnNumber("GRN-" + id);
        receipt.setOrganization(organization);
        receipt.setProject(project);
        return receipt;
    }

    /** Grants read, create and edit on one project's receipts, and nothing else. */
    private static final class OneProjectAccess implements AccessService {

        boolean everything;
        boolean nothing;
        boolean mayEdit = true;

        private Set<String> held() {
            return mayEdit
                    ? Set.of(AccessPermission.READ, AccessPermission.CREATE, AccessPermission.EDIT)
                    : Set.of(AccessPermission.READ, AccessPermission.CREATE);
        }

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return true;
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return !nothing && held().contains(permission.action());
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            if (nothing || !held().contains(permission.action())) {
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
