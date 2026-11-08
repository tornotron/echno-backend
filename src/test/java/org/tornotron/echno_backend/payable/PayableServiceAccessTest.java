package org.tornotron.echno_backend.payable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteRepository;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;
import org.tornotron.echno_backend.payable.mapper.PayableMapper;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.VendorRepository;

/**
 * The payable service asks the access matrix about each payable, not only about the area: a caller whose
 * grants reach one project sees and records only that project's payables, and a payment needs edit on
 * the payable it is recorded against. Contract billing raises a payable and reverses a payment on one
 * without asking.
 */
@ExtendWith(MockitoExtension.class)
class PayableServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;
    private static final Long RAISED_BY = 7L;
    private static final Long VENDOR = 30L;

    @Mock private PayableRepository payableRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    @Mock private UserRepository userRepository;
    @Mock private PayableMapper payableMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProject access = new OneProject(MY_PROJECT);
    private PayableService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        Employee caller = new Employee();
        caller.setId(RAISED_BY);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        service = new PayableService(payableRepository, vendorRepository, goodsReceivedNoteRepository,
                userRepository, payableMapper, tenantEntityHelper, employeeRepository, projectRepository,
                new PayableAccess(access, currentEmployee));
        lenient().when(payableRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG))
                .thenAnswer(call -> Optional.of(payable(ON_MY_PROJECT, MY_PROJECT)));
        lenient().when(payableRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG))
                .thenAnswer(call -> Optional.of(payable(ELSEWHERE, OTHER_PROJECT)));
        lenient().when(payableRepository.lockByIdAndOrganizationId(ON_MY_PROJECT, ORG))
                .thenAnswer(call -> Optional.of(payable(ON_MY_PROJECT, MY_PROJECT)));
        lenient().when(payableRepository.lockByIdAndOrganizationId(ELSEWHERE, ORG))
                .thenAnswer(call -> Optional.of(payable(ELSEWHERE, OTHER_PROJECT)));
        lenient().when(employeeRepository.findByIdAndOrganizationId(RAISED_BY, ORG)).thenReturn(Optional.of(caller));
        lenient().when(projectRepository.findByIdAndOrganization_Id(anyLong(), any()))
                .thenAnswer(call -> Optional.of(project(call.getArgument(0))));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        lenient().when(payableRepository.save(any(Payable.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aPayableTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getPayableById(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.recordPayment(ELSEWHERE, BigDecimal.TEN));
        verify(payableRepository, never()).save(any());
    }

    @Test
    void aPayableTheCallerMaySeeIsReturned() {
        service.getPayableById(ON_MY_PROJECT);

        verify(payableMapper).toDto(any(Payable.class));
    }

    @Test
    void aPaymentNeedsEditOnThePayable() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.recordPayment(ON_MY_PROJECT, BigDecimal.TEN));
        verify(payableRepository, never()).save(any());

        access.held = Set.of(AccessPermission.READ, AccessPermission.EDIT);
        service.recordPayment(ON_MY_PROJECT, BigDecimal.TEN);
        verify(payableRepository).save(any());
    }

    @Test
    void aPayableIsRecordedOnlyWhereTheCallerMayRecordOne() {
        service.createPayable(record(MY_PROJECT));

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.createPayable(record(OTHER_PROJECT)));
        verify(payableRepository).save(any());
    }

    @Test
    void contractBillingRaisesAndReversesWithoutAskingThePayablesArea() {
        access.nothing = true;

        service.raiseForApprovedBill(record(OTHER_PROJECT));
        verify(payableRepository).save(any());

        // A bill payment the bank returned is taken back under billing's guard, also without asking.
        Payable paid = payable(ELSEWHERE, OTHER_PROJECT);
        paid.setAmountPaid(new BigDecimal("50.00"));
        when(payableRepository.lockByIdAndOrganizationId(ELSEWHERE, ORG)).thenReturn(Optional.of(paid));
        service.reversePayment(ELSEWHERE, BigDecimal.TEN);
        assertThat(paid.getAmountPaid()).isEqualByComparingTo("40.00");
    }

    @Test
    void theListsAreNarrowedInTheQuery() {
        when(payableRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(payableRepository.findAll(any(Specification.class))).thenReturn(List.of());

        service.getAllPayables(0, 10);
        service.getPayablesByVendor(VENDOR);
        service.getPayablesByVendor(VENDOR, 0, 10);
        service.getOutstandingPayables();
        service.getOutstandingPayables(0, 10);

        verify(payableRepository, times(3)).findAll(any(Specification.class), any(Pageable.class));
        verify(payableRepository, times(2)).findAll(any(Specification.class));
        verify(payableRepository, never()).findAll(any(Pageable.class));
        verify(payableRepository, never()).findByVendorIdAndOrganization_id(any(), any());
        verify(payableRepository, never()).findByVendorIdAndOrganization_id(any(), any(), any());
        verify(payableRepository, never()).findOutstandingPayables();
        verify(payableRepository, never()).findOutstandingPayables(any(Pageable.class));
    }

    @Test
    void aCallerWhoMaySeeEveryPayableGetsTheUnnarrowedQueries() {
        access.everything = true;
        when(payableRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        // Lenient, since the list forms call the other overload of the same methods.
        lenient().when(payableRepository.findByVendorIdAndOrganization_id(any(), any(), any())).thenReturn(Page.empty());
        lenient().when(payableRepository.findOutstandingPayables(any(Pageable.class))).thenReturn(Page.empty());

        service.getAllPayables(0, 10);
        service.getPayablesByVendor(VENDOR);
        service.getPayablesByVendor(VENDOR, 0, 10);
        service.getOutstandingPayables();
        service.getOutstandingPayables(0, 10);

        Pageable newestFirst = PageRequest.of(0, 10,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        verify(payableRepository).findAll(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
        verify(payableRepository).findByVendorIdAndOrganization_id(VENDOR, ORG);
        verify(payableRepository).findByVendorIdAndOrganization_id(VENDOR, ORG, newestFirst);
        verify(payableRepository).findOutstandingPayables();
        verify(payableRepository).findOutstandingPayables(newestFirst);
        verify(payableRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(payableRepository, never()).findAll(any(Specification.class));
    }

    @Test
    void aCallerWhoMaySeeNoPayableIsNotQueriedFor() {
        access.nothing = true;

        assertThat(service.getAllPayables(0, 10)).isEmpty();
        assertThat(service.getPayablesByVendor(VENDOR)).isEmpty();
        assertThat(service.getOutstandingPayables()).isEmpty();
        assertThat(service.getPayablesByVendor(VENDOR, 0, 10)).isEmpty();
        assertThat(service.getOutstandingPayables(0, 10)).isEmpty();
        verify(payableRepository, never()).findAll(any(Pageable.class));
        verify(payableRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(payableRepository, never()).findAll(any(Specification.class));
        verify(payableRepository, never()).findByVendorIdAndOrganization_id(any(), any());
        verify(payableRepository, never()).findOutstandingPayables();
        verify(payableRepository, never()).findByVendorIdAndOrganization_id(any(), any(), any());
        verify(payableRepository, never()).findOutstandingPayables(any(Pageable.class));
    }

    private Payable payable(Long id, Long projectId) {
        Payable payable = new Payable();
        payable.setId(id);
        payable.setOrganization(organization);
        payable.setPayableNumber("PAY-" + id);
        payable.setContractorName("Contractor " + id);
        payable.setAmountRecorded(new BigDecimal("1000.00"));
        payable.setAmountPaid(BigDecimal.ZERO);
        payable.setProject(project(projectId));
        return payable;
    }

    private static Project project(Long id) {
        Project project = new Project();
        project.setId(id);
        return project;
    }

    private static PayableCreationDto record(Long projectId) {
        PayableCreationDto dto = new PayableCreationDto();
        dto.setPayableNumber("PAY-NEW");
        dto.setContractorName("ACME");
        dto.setContractType("SUBCONTRACTOR");
        dto.setAmountRecorded(new BigDecimal("100.00"));
        dto.setProjectId(projectId);
        dto.setCreatedBy(RAISED_BY);
        return dto;
    }

    /** An engine holding some payables actions on one project's records, or on everything, or nothing. */
    private static final class OneProject implements AccessService {

        private final Long project;
        Set<String> held = Set.of(AccessPermission.READ, AccessPermission.CREATE);
        boolean everything;
        boolean nothing;

        OneProject(Long project) {
            this.project = project;
        }

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return true;
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return !nothing && held.contains(permission.action());
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            if (nothing || !held.contains(permission.action()) || !PayableAccess.AREA.equals(permission.area())) {
                return AccessFilter.none(subject.organizationId());
            }
            if (everything) {
                return AccessFilter.all(subject.organizationId());
            }
            return new AccessFilter(subject.organizationId(), false, Set.of(project), null, null);
        }

        @Override
        public AccessGrants grantsFor(AccessSubject subject) {
            return AccessGrants.none();
        }
    }
}
