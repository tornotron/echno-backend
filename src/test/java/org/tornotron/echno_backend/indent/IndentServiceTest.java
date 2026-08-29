package org.tornotron.echno_backend.indent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.indent.dto.IndentCreationDto;
import org.tornotron.echno_backend.indent.enums.IndentStatus;
import org.tornotron.echno_backend.indent.mapper.IndentMapper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the part of indent creation this change owns: the indent number comes from
 * the server, not from whatever the browser worked out from the list it had loaded.
 */
@ExtendWith(MockitoExtension.class)
class IndentServiceTest {

    private static final Long ORG = 100L;

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

    private IndentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new IndentService(indentRepository, indentItemRepository, materialRepository,
                indentMapper, tenantEntityHelper, employeeRepository, projectRepository,
                indentItemMapper, documentNumberAllocator, retryTemplate, inventoryService);
        lenient().when(retryTemplate.execute(anyString(), any(Predicate.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void addIndent_takesItsNumberFromTheServerNotTheCaller() {
        Employee employee = new Employee();
        employee.setId(7L);
        Project project = new Project();
        project.setId(9L);
        Organization org = new Organization();
        org.setId(ORG);
        lenient().when(employeeRepository.findByIdAndOrganizationId(7L, ORG)).thenReturn(Optional.of(employee));
        lenient().when(projectRepository.findByIdAndOrganization_Id(9L, ORG)).thenReturn(Optional.of(project));
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(org);
        lenient().when(indentRepository.save(any(Indent.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentNumberAllocator.allocate(DocumentNumberType.INDENT, ORG))
                .thenReturn("IND-2026-000042");

        IndentCreationDto dto = new IndentCreationDto();
        dto.setCreatedByEmployeeId(7L);
        dto.setProjectId(9L);
        dto.setStatus(IndentStatus.PENDING.name());

        service.addIndent(dto);

        ArgumentCaptor<Indent> captor = ArgumentCaptor.forClass(Indent.class);
        verify(indentRepository).save(captor.capture());
        assertThat(captor.getValue().getIndentNumber()).isEqualTo("IND-2026-000042");
        verify(documentNumberAllocator).allocate(DocumentNumberType.INDENT, ORG);
    }
}
