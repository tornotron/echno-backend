package org.tornotron.echno_backend.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.category.mapper.CategoryMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectAccess;

/**
 * A work category belongs to its organization and to no project, so a caller whose projects grants
 * reach only some projects reaches no category, and one whose grants cover the organization reaches
 * every one.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long CATEGORY = 5L;

    @Mock private CategoryRepository categoryRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private WorkSubcategoryRepository workSubcategoryRepository;
    @Mock private CurrentEmployeeService currentEmployee;

    private final ScopedAccess access = new ScopedAccess();
    private CategoryService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        Employee caller = new Employee();
        caller.setId(7L);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        service = new CategoryService(categoryRepository, tenantEntityHelper, categoryMapper,
                workSubcategoryRepository, new ProjectAccess(access, currentEmployee));
        Category category = new Category();
        category.setId(CATEGORY);
        lenient().when(categoryRepository.findByIdAndOrganization_Id(CATEGORY, ORG)).thenReturn(Optional.of(category));
        lenient().when(categoryRepository.existsByIdAndOrganization_Id(CATEGORY, ORG)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aCallerWhoseGrantsReachOnlySomeProjectsReachesNoCategory() {
        assertThat(service.getAllCategories(0, 10)).isEmpty();
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getACategory(CATEGORY));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getSubcategories(CATEGORY));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.deleteACategory(CATEGORY));
        verify(categoryRepository, never()).findAll(any(Pageable.class));
        verify(categoryRepository, never()).deleteById(any());
    }

    @Test
    void aCallerWhoseGrantsCoverTheOrganizationReachesEveryCategory() {
        access.everything = true;
        when(categoryRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        service.getAllCategories(0, 10);
        service.getACategory(CATEGORY);

        verify(categoryRepository).findAll(any(Pageable.class));
    }

    @Test
    void aCategoryTheCallerMayReadButNotChangeIsRefusedAndKept() {
        access.everything = true;
        Organization organization = new Organization();
        organization.setId(ORG);
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        CategoryCreationDto dto = new CategoryCreationDto();
        dto.setName("Earthwork");

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.deleteACategory(CATEGORY));
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.addCategory(dto));
        verify(categoryRepository, never()).deleteById(any());
        verify(categoryRepository, never()).save(any());
    }

    /** Grants read only: on every record of the organization, or only on one project's. */
    private static final class ScopedAccess implements AccessService {

        boolean everything;

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return true;
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return AccessPermission.READ.equals(permission.action());
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            if (!AccessPermission.READ.equals(permission.action())) {
                return AccessFilter.none(subject.organizationId());
            }
            return everything
                    ? AccessFilter.all(subject.organizationId())
                    : new AccessFilter(subject.organizationId(), false, Set.of(10L), null, null);
        }

        @Override
        public AccessGrants grantsFor(AccessSubject subject) {
            return AccessGrants.none();
        }
    }
}
