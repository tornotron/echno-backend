package org.tornotron.echno_backend.indent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.tornotron.echno_backend.indent.dto.IndentUpdateDto;
import org.tornotron.echno_backend.indent.mapper.IndentMapper;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.indentItem.IndentItemService;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
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

/**
 * Indents and their lines ask the access matrix about each indent, not only about the area: a caller
 * whose grants reach one project sees and changes only that project's indents and their lines.
 */
@ExtendWith(MockitoExtension.class)
class IndentServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long LINE_ON_MY_PROJECT = 11L;
    private static final Long LINE_ELSEWHERE = 12L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;

    @Mock private IndentRepository indentRepository;
    @Mock private IndentItemRepository indentItemRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private IndentMapper indentMapper;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private IndentItemMapper indentItemMapper;
    @Mock private DocumentNumberAllocator documentNumberAllocator;
    @Mock private TransactionRetryTemplate retryTemplate;
    @Mock private InventoryService inventoryService;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProjectAccess access = new OneProjectAccess();
    private IndentService indents;
    private IndentItemService lines;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        Employee caller = new Employee();
        caller.setId(7L);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        IndentAccess indentAccess = new IndentAccess(access, currentEmployee);
        indents = new IndentService(indentRepository, indentItemRepository, materialRepository, indentMapper,
                tenantEntityHelper, employeeRepository, projectRepository, indentItemMapper,
                documentNumberAllocator, retryTemplate, inventoryService, indentAccess);
        lines = new IndentItemService(indentItemRepository, indentRepository, materialRepository,
                tenantEntityHelper, indentItemMapper, inventoryService, indentAccess);

        Indent mine = indent(ON_MY_PROJECT, MY_PROJECT);
        Indent theirs = indent(ELSEWHERE, OTHER_PROJECT);
        lenient().when(indentRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG)).thenReturn(Optional.of(mine));
        lenient().when(indentRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG)).thenReturn(Optional.of(theirs));
        lenient().when(indentItemRepository.findByIdAndOrganization_Id(LINE_ON_MY_PROJECT, ORG))
                .thenReturn(Optional.of(line(LINE_ON_MY_PROJECT, mine)));
        lenient().when(indentItemRepository.findByIdAndOrganization_Id(LINE_ELSEWHERE, ORG))
                .thenReturn(Optional.of(line(LINE_ELSEWHERE, theirs)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anIndentTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> indents.getAnIndent(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> indents.getItemsByIndentId(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> indents.deleteIndent(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> indents.deleteItem(ELSEWHERE, LINE_ELSEWHERE));
        verify(indentRepository, never()).deleteByIdAndOrganization_Id(anyLong(), anyLong());
    }

    @Test
    void aLineOfAnIndentTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> lines.getIndentItemById(LINE_ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> lines.deleteIndentItem(LINE_ELSEWHERE));
        verify(indentItemRepository, never()).deleteByIdAndOrganization_Id(anyLong(), anyLong());
    }

    @Test
    void anIndentTheCallerMaySeeButNotDeleteIsRefusedAndKept() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> indents.deleteIndent(ON_MY_PROJECT));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> indents.deleteItem(ON_MY_PROJECT, LINE_ON_MY_PROJECT));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> lines.deleteIndentItem(LINE_ON_MY_PROJECT));
        verify(indentRepository, never()).deleteByIdAndOrganization_Id(anyLong(), anyLong());
        verify(indentItemRepository, never()).delete(any(IndentItem.class));
        verify(indentItemRepository, never()).deleteByIdAndOrganization_Id(anyLong(), anyLong());
    }

    @Test
    void anIndentCannotBeMovedOutOfWhatTheCallerMayEdit() {
        Project other = new Project();
        other.setId(OTHER_PROJECT);
        when(projectRepository.findByIdAndOrganization_Id(OTHER_PROJECT, ORG)).thenReturn(Optional.of(other));
        IndentUpdateDto move = new IndentUpdateDto();
        move.setProjectId(OTHER_PROJECT);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> indents.updateIndent(ON_MY_PROJECT, move));
        verify(indentRepository, never()).save(any());
    }

    @Test
    void aLineCannotBeMovedOntoAnIndentTheCallerMayNotSee() {
        IndentItemCreationDto move = new IndentItemCreationDto();
        move.setIndentId(ELSEWHERE);
        move.setMaterialId(5L);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> lines.updateIndentItem(LINE_ON_MY_PROJECT, move));
        verify(indentItemRepository, never()).save(any());
    }

    @Test
    void theListsAreNarrowedInTheQuery() {
        when(indentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(indentItemRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        indents.getAllIndents(0, 10);
        indents.getAllIndentsSummary(0, 10);
        lines.getIndentItemsPaginated(0, 10);
        lines.getIndentItemsByIndentId(ON_MY_PROJECT);

        verify(indentRepository, never()).findAll(any(Pageable.class));
        verify(indentItemRepository, never()).findAll(any(Pageable.class));
        verify(indentItemRepository).findAll(any(Specification.class));
        verify(indentItemRepository, never()).findByIndentId(anyLong());
    }

    @Test
    void aCallerWhoMaySeeEveryIndentGetsTheUnnarrowedList() {
        access.everything = true;
        when(indentRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(indentItemRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        indents.getAllIndents(0, 10);
        lines.getIndentItemsPaginated(0, 10);
        lines.getIndentItemsByIndentId(ON_MY_PROJECT);

        verify(indentRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(indentItemRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(indentItemRepository).findByIndentId(ON_MY_PROJECT);
    }

    @Test
    void aCallerWhoMaySeeNoIndentIsNotQueriedFor() {
        access.nothing = true;

        assertThat(indents.getAllIndents(0, 10)).isEmpty();
        assertThat(indents.getAllIndentsSummary(0, 10)).isEmpty();
        assertThat(lines.getIndentItemsPaginated(0, 10)).isEmpty();
        assertThat(lines.getIndentItemsByMaterialId(5L)).isEmpty();
        assertThat(lines.getIndentItemsByConversionStatus(false)).isEmpty();
        verify(indentRepository, never()).findAll(any(Pageable.class));
        verify(indentItemRepository, never()).findAll(any(Pageable.class));
        verify(indentItemRepository, never()).findByMaterialId(anyLong());
        verify(indentItemRepository, never()).findByConvertedToPurchaseOrder(any());
    }

    @Test
    void theReportReadsEveryIndentWhateverTheCallerMaySee() {
        access.nothing = true;
        when(indentRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        indents.getAllIndentsForReport(0, 10);

        verify(indentRepository).findAll(any(Pageable.class));
    }

    private Indent indent(Long id, Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        Indent indent = new Indent();
        indent.setId(id);
        indent.setOrganization(organization);
        indent.setProject(project);
        return indent;
    }

    private IndentItem line(Long id, Indent indent) {
        IndentItem line = new IndentItem();
        line.setId(id);
        line.setOrganization(organization);
        line.setIndent(indent);
        return line;
    }

    /** Grants read and edit on one project's indents, and nothing else. */
    private static final class OneProjectAccess implements AccessService {

        private static final Set<String> HELD = Set.of(AccessPermission.READ, AccessPermission.EDIT);

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
