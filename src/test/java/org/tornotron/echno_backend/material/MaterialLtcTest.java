package org.tornotron.echno_backend.material;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.dto.MaterialCreationDto;
import org.tornotron.echno_backend.material.dto.MaterialUpdateDto;
import org.tornotron.echno_backend.material.mapper.MaterialMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lead time consumption on a material, on the way in.
 *
 * <p>LTC is a labelled input on the material create and edit forms and is sent on both. Nothing
 * in the backend had it under that or any other name, and the client uses it to recompute the
 * minimum stock, reorder level and maximum stock, so the derived numbers were stored while the
 * input behind them was thrown away. Reopening the form then recomputed from a blank field.
 *
 * <p>The update is a bound DTO, a run of {@code if (field != null)} blocks rather than a map and
 * a switch, so no schema contract test can notice a field the run forgets. That is what the last
 * two of these are for.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialLtcTest {

    @Mock private MaterialRepository materialRepository;
    @Mock private InventoryService inventoryService;
    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private MaterialMapper materialMapper;
    @Mock private UserContextService userContextService;

    private MaterialService service;

    @BeforeEach
    void setUp() {
        service = new MaterialService(materialRepository, inventoryService,
                inventoryTransactionRepository, tenantEntityHelper, employeeRepository,
                projectRepository, storageLocationRepository, materialMapper, userContextService);

        TenantContext.setCurrentOrgId(1L);

        Organization organization = new Organization();
        organization.setId(1L);
        Employee createdBy = new Employee();
        createdBy.setId(7L);

        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        when(employeeRepository.findByIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.of(createdBy));
        when(materialRepository.existsBySkuAndOrganization_Id(anyString(), any())).thenReturn(false);
        when(materialRepository.save(any(Material.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private MaterialCreationDto creationDto() {
        MaterialCreationDto dto = new MaterialCreationDto();
        dto.setMaterialName("TMT Bar 12mm");
        dto.setUnit("kg");
        dto.setCreatedBy(7L);
        return dto;
    }

    @Test
    @DisplayName("create stores the lead time consumption the form submitted")
    void createMaterial_storesLtc() {
        MaterialCreationDto dto = creationDto();
        dto.setLtc(250.0);

        service.createMaterial(dto);

        assertThat(savedMaterial().getLtc()).isEqualTo(250.0);
    }

    @Test
    @DisplayName("create leaves the lead time consumption null when the payload omits it")
    void createMaterial_leavesLtcNullWhenAbsent() {
        service.createMaterial(creationDto());

        assertThat(savedMaterial().getLtc()).isNull();
    }

    @Test
    @DisplayName("update applies the lead time consumption")
    void updateMaterial_appliesLtc() {
        Material existing = existingMaterial(250.0);

        MaterialUpdateDto update = new MaterialUpdateDto();
        update.setLtc(400.0);
        service.updateMaterial(12L, update);

        assertThat(existing.getLtc()).isEqualTo(400.0);
    }

    @Test
    @DisplayName("update leaves the stored lead time consumption alone when the payload omits it")
    void updateMaterial_leavesLtcAloneWhenAbsent() {
        // The contract every field on a bound update DTO has to keep: a null means "not sent",
        // never "set it to null". Nothing but a test enforces it on this endpoint.
        Material existing = existingMaterial(250.0);

        MaterialUpdateDto update = new MaterialUpdateDto();
        update.setMaterialName("TMT Bar 16mm");
        service.updateMaterial(12L, update);

        assertThat(existing.getLtc()).isEqualTo(250.0);
        assertThat(existing.getMaterialName()).isEqualTo("TMT Bar 16mm");
    }

    private Material existingMaterial(Double ltc) {
        Material material = new Material();
        material.setId(12L);
        material.setMaterialName("TMT Bar 12mm");
        material.setUnit("kg");
        material.setLtc(ltc);
        when(materialRepository.findByIdAndOrganization_Id(any(), any()))
                .thenReturn(Optional.of(material));
        return material;
    }

    private Material savedMaterial() {
        ArgumentCaptor<Material> captor = ArgumentCaptor.forClass(Material.class);
        verify(materialRepository).save(captor.capture());
        return captor.getValue();
    }
}
