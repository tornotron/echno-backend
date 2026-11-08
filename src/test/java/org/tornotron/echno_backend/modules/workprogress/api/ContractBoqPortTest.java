package org.tornotron.echno_backend.modules.workprogress.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.workprogress.WorkProgressModule;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBoqPortAdapter;
import org.tornotron.echno_backend.modules.workprogress.billing.web.ContractBillingController;
import org.tornotron.echno_backend.modules.workprogress.billing.web.ContractBillingControllerWeb;
import org.tornotron.echno_backend.subcontract.SubContractRepository;

/**
 * The BOQ port asks what the BOQ endpoint asks: the same guard, and the same field limits before the
 * same service call.
 */
class ContractBoqPortTest {

    @Test
    void theWriteGuardIsTheGuardOnBothBoqEndpoints() throws Exception {
        for (Class<?> controller : new Class<?>[] {ContractBillingController.class, ContractBillingControllerWeb.class}) {
            Method add = controller.getMethod("addBoqItem", Long.class, BoqItemRequest.class);
            assertThat(add.getAnnotation(PreAuthorize.class).value()).as(controller.getSimpleName())
                    .isEqualTo(ContractBoqPort.WRITE_GUARD);
        }
        assertThat(ContractBoqPort.MODULE_ID).isEqualTo(WorkProgressModule.ID);
    }

    @Test
    void aLineWithinTheEndpointsLimitsGoesToTheBoqService() {
        ContractBillingService billing = mock(ContractBillingService.class);
        UUID id = UUID.randomUUID();
        BoqItemDto dto = mock(BoqItemDto.class);
        when(dto.id()).thenReturn(id);
        when(billing.addBoqItem(eq(7L), any())).thenReturn(dto);
        ContractBoqPort port = new ContractBoqPortAdapter(billing, mock(SubContractRepository.class));

        UUID added = port.addItem(7L, new ContractBoqPort.BoqLine("1.03", "RCC M25", "m3", new BigDecimal("850"),
                new BigDecimal("9800"), 1_000_004));

        assertThat(added).isEqualTo(id);
    }

    @Test
    void aLineOutsideTheEndpointsLimitsIsRefusedBeforeTheService() {
        ContractBillingService billing = mock(ContractBillingService.class);
        ContractBoqPort port = new ContractBoqPortAdapter(billing, mock(SubContractRepository.class));

        assertThatThrownBy(() -> port.addItem(7L, new ContractBoqPort.BoqLine("X".repeat(51), "d", "u".repeat(31),
                BigDecimal.ZERO, new BigDecimal("1.234"), null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("itemCode").hasMessageContaining("unit")
                .hasMessageContaining("contractQuantity").hasMessageContaining("rate");
        assertThatThrownBy(() -> port.addItem(7L, new ContractBoqPort.BoqLine("1", " ", "m", BigDecimal.ONE, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("description").hasMessageContaining("rate");
        verify(billing, never()).addBoqItem(any(), any());
    }
}
