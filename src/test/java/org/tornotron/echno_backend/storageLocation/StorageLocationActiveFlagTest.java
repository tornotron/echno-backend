package org.tornotron.echno_backend.storageLocation;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationCreationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationUpdateDto;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.storageLocation.mapper.StorageLocationMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The active flag on a storage location, on both write paths.
 *
 * <p>A storage location could not be deactivated at all, and the cause was a naming split inside
 * the backend rather than anything the client did. Lombok names a primitive {@code boolean
 * isActive}'s accessors {@code isActive()}/{@code setActive()}, which Jackson publishes as
 * {@code active}; a wrapper {@code Boolean isActive} gets {@code getIsActive()}/{@code
 * setIsActive()} and publishes {@code isActive}. The create payload was the primitive and the
 * update payload the wrapper, so the same field went out under two names, the client sent
 * {@code active} to both, and every deactivate bound to nothing and was dropped with a 200.
 *
 * <p>Both payloads settled on {@code isActive}, each carrying a transitional {@code @JsonAlias}
 * for {@code active} so the deployed client kept working while echno-core caught up. That was the
 * expand step. This is the contract step: the aliases are gone, and the two tests that pinned the
 * older spelling now assert it is <em>not</em> bound. They fail if either alias comes back.
 *
 * <p>The ordering that made the deletion safe, checked rather than assumed: echno-core emits
 * {@code isActive} on both payloads from v3.3.0; echno-web declares {@code ^3.4.0} with its
 * lockfile pinned there; and the staging build deployed from that lockfile is the one serving
 * traffic. Deleting the aliases before that would not have failed loudly. Jackson ignores an
 * undeclared property rather than refusing it, so a deactivate sent under the old name would have
 * gone back to answering 200 and changing nothing, which is the exact bug the expand step fixed.
 *
 * <p>The create payload also has a second fault of its own: a primitive defaults to false, and
 * the service applied it unconditionally, so a create that named the key at all made the location
 * inactive and the entity's own default of true never stood. That is the last test here.
 *
 * <p>Binding goes through {@link Jackson2ObjectMapperBuilder}, which is what Spring Boot's
 * Jackson auto-configuration builds the injected mapper from, so what binds here is what binds
 * on the endpoint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StorageLocationActiveFlagTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private StorageLocationMapper storageLocationMapper;
    @Mock private InventoryService inventoryService;

    private StorageLocationService service;

    @BeforeEach
    void setUp() {
        service = new StorageLocationService(storageLocationRepository, projectRepository,
                tenantEntityHelper, storageLocationMapper, inventoryService);

        TenantContext.setCurrentOrgId(1L);
        Organization organization = new Organization();
        organization.setId(1L);
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        when(storageLocationRepository.existsByLocationNameAndOrganization_Id(anyString(), any()))
                .thenReturn(false);
        when(storageLocationRepository.save(any(StorageLocation.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the update payload binds the canonical isActive")
    void updateDto_bindsIsActive() throws Exception {
        StorageLocationUpdateDto dto =
                mapper.readValue("{\"isActive\":false}", StorageLocationUpdateDto.class);

        assertThat(dto.getIsActive()).isFalse();
    }

    @Test
    @DisplayName("the update payload no longer binds the retired name active")
    void updateDto_ignoresTheRetiredActive() throws Exception {
        StorageLocationUpdateDto dto =
                mapper.readValue("{\"active\":false}", StorageLocationUpdateDto.class);

        assertThat(dto.getIsActive()).isNull();
    }

    @Test
    @DisplayName("the create payload binds the canonical isActive")
    void creationDto_bindsIsActive() throws Exception {
        StorageLocationCreationDto dto =
                mapper.readValue("{\"isActive\":false}", StorageLocationCreationDto.class);

        assertThat(dto.getIsActive()).isFalse();
    }

    @Test
    @DisplayName("the create payload no longer binds the retired name active")
    void creationDto_ignoresTheRetiredActive() throws Exception {
        StorageLocationCreationDto dto =
                mapper.readValue("{\"active\":false}", StorageLocationCreationDto.class);

        assertThat(dto.getIsActive()).isNull();
    }

    @Test
    @DisplayName("a create payload naming neither spelling leaves the flag unset")
    void creationDto_leavesTheFlagUnsetWhenNeitherNameIsSent() throws Exception {
        StorageLocationCreationDto dto = mapper.readValue(
                "{\"locationName\":\"Central Warehouse\",\"locationType\":\"WAREHOUSE\"}",
                StorageLocationCreationDto.class);

        assertThat(dto.getIsActive()).isNull();
    }

    @Test
    @DisplayName("a deactivate sent as isActive reaches the entity")
    void updateStorageLocation_deactivatesFromTheCanonicalName() throws Exception {
        StorageLocation existing = new StorageLocation();
        existing.setLocationName("Central Warehouse");
        existing.setLocationType(StorageLocationType.WAREHOUSE);
        when(storageLocationRepository.findByIdAndOrganization_Id(any(), any()))
                .thenReturn(Optional.of(existing));

        service.updateStorageLocation(18L,
                mapper.readValue("{\"isActive\":false}", StorageLocationUpdateDto.class));

        assertThat(existing.isActive()).isFalse();
    }

    @Test
    @DisplayName("a create naming the retired active is still active, so the deletion costs no data")
    void createStorageLocation_stillActiveWhenTheRetiredNameIsSent() throws Exception {
        // The two halves of the retirement do not cost the same. An old caller's create arrives
        // with the flag unset rather than false, so the null guard hands it the entity default and
        // the location is active, which is what it wanted. Only a deactivate is lost, and nothing
        // deployed sends one under the old name.
        service.createStorageLocation(mapper.readValue(
                "{\"locationName\":\"Old Godown\",\"locationType\":\"GODOWN\",\"active\":false}",
                StorageLocationCreationDto.class));

        assertThat(savedLocation().isActive()).isTrue();
    }

    @Test
    @DisplayName("a create naming neither spelling leaves the location active")
    void createStorageLocation_keepsTheEntityDefaultWhenThePayloadIsSilent() throws Exception {
        service.createStorageLocation(mapper.readValue(
                "{\"locationName\":\"Central Warehouse\",\"locationType\":\"WAREHOUSE\"}",
                StorageLocationCreationDto.class));

        assertThat(savedLocation().isActive()).isTrue();
    }

    @Test
    @DisplayName("a create that asks for an inactive location gets one")
    void createStorageLocation_appliesAnExplicitFalse() throws Exception {
        service.createStorageLocation(mapper.readValue(
                "{\"locationName\":\"Old Godown\",\"locationType\":\"GODOWN\",\"isActive\":false}",
                StorageLocationCreationDto.class));

        assertThat(savedLocation().isActive()).isFalse();
    }

    private StorageLocation savedLocation() {
        ArgumentCaptor<StorageLocation> captor = ArgumentCaptor.forClass(StorageLocation.class);
        verify(storageLocationRepository).save(captor.capture());
        return captor.getValue();
    }
}
