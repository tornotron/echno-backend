package org.tornotron.echno_backend.common.events.listeners;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.events.MaterialConsumedEvent;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger entry a consumption writes has to describe the balance row the consumption
 * moves. With no storage location that row is the project's unlocated balance, so reading
 * the project total instead would stamp an opening and closing figure on the entry that
 * the row it moved never held.
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventListenerConsumptionTest {

    private static final Long ORG = 100L;
    private static final Long MATERIAL = 2L;
    private static final Long PROJECT = 3L;
    private static final Long LOCATION = 14L;

    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private InventoryService inventoryService;

    private InventoryEventListener listener;
    private Material material;
    private Project project;
    private Organization organization;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventListener(inventoryTransactionRepository, inventoryService);
        material = new Material();
        material.setId(MATERIAL);
        project = new Project();
        project.setId(PROJECT);
        organization = new Organization();
        organization.setId(ORG);
    }

    private MaterialConsumption consumption(StorageLocation location) {
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setId(31L);
        consumption.setConsumptionDate(LocalDateTime.now());
        consumption.setMaterial(material);
        consumption.setProject(project);
        consumption.setOrganization(organization);
        consumption.setStorageLocation(location);
        consumption.setQuantity(4);
        consumption.setConsumptionType(MaterialConsumptionType.USED_FROM_STOCK);
        return consumption;
    }

    @Test
    void aConsumptionWithNoLocationOpensFromTheProjectsUnlocatedBalance() {
        when(inventoryService.findUnlocatedStock(MATERIAL, PROJECT)).thenReturn(Optional.of(10.0));

        listener.handleMaterialConsumed(new MaterialConsumedEvent(this, consumption(null)));

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getOpeningStock()).isEqualTo(10.0);
        assertThat(captor.getValue().getQuantityChanged()).isEqualTo(-4.0);
        assertThat(captor.getValue().getClosingStock()).isEqualTo(6.0);
        verify(inventoryService, never()).getCurrentStock(any(), any());
    }

    @Test
    void aConsumptionAtALocationOpensFromThatLocationsBalance() {
        StorageLocation location = new StorageLocation();
        location.setId(LOCATION);
        when(inventoryService.getStockAtLocation(MATERIAL, PROJECT, LOCATION)).thenReturn(10.0);

        listener.handleMaterialConsumed(new MaterialConsumedEvent(this, consumption(location)));

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getOpeningStock()).isEqualTo(10.0);
        assertThat(captor.getValue().getClosingStock()).isEqualTo(6.0);
    }
}
