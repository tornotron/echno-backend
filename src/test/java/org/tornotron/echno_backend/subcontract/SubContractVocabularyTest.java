package org.tornotron.echno_backend.subcontract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.subcontract.dto.SubContractCreationDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractDto;
import org.tornotron.echno_backend.subcontract.enums.SubContractPaymentStatus;
import org.tornotron.echno_backend.subcontract.mapper.SubContractMapper;
import org.tornotron.echno_backend.subcontract.mapper.SubContractMapperImpl;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * Sub-contract type, status and payment terms are held to the vocabularies the web renders, and
 * the list carries a payment status (#863). All three used to be stored as sent, so the form and
 * the badge drifted onto different value sets, and the payment badge read a field the DTO never
 * had.
 */
@ExtendWith(MockitoExtension.class)
class SubContractVocabularyTest {

    @Mock private SubContractRepository subContractRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;

    private final SubContractMapper mapper = new SubContractMapperImpl();
    private SubContractService service;

    @BeforeEach
    void setUp() {
        service = new SubContractService(subContractRepository, mapper, tenantEntityHelper);
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(new Organization());
        lenient().when(subContractRepository.saveAndFlush(any(SubContract.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static SubContractCreationDto dto(String type, String status, String paymentTerms) {
        SubContractCreationDto dto = new SubContractCreationDto();
        dto.setContractName("Tower B shuttering");
        dto.setContractorName("Malabar Formworks");
        dto.setType(type);
        dto.setStatus(status);
        dto.setPaymentTerms(paymentTerms);
        return dto;
    }

    @Test
    void create_acceptsTheVocabulary() {
        SubContractDto created = service.create(dto("itemRate", "onHold", "milestone"));

        assertThat(created.getType()).isEqualTo("itemRate");
        assertThat(created.getStatus()).isEqualTo("onHold");
        assertThat(created.getPaymentTerms()).isEqualTo("milestone");
    }

    @Test
    void create_refusesAStatusOutsideTheVocabulary() {
        assertThatThrownBy(() -> service.create(dto("lumpsum", "suspended", "milestone")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("status")
                .hasMessageContaining("onHold");
        verify(subContractRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_refusesATypeOutsideTheVocabulary() {
        assertThatThrownBy(() -> service.create(dto("construction", "active", "milestone")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("type");
    }

    @Test
    void create_refusesPaymentTermsOutsideTheVocabulary() {
        assertThatThrownBy(() -> service.create(dto("lumpsum", "active", "NET45")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("paymentTerms");
    }

    @Test
    void create_leavesUnsetFieldsUnset() {
        SubContractDto created = service.create(dto(null, null, ""));

        assertThat(created.getType()).isNull();
        assertThat(created.getStatus()).isNull();
        assertThat(created.getPaymentTerms()).isNull();
    }

    @Test
    void theDtoCarriesAPaymentStatus() {
        SubContract contract = new SubContract();
        contract.setContractValue(new BigDecimal("1000.00"));
        contract.setTotalPaid(new BigDecimal("400.00"));
        contract.setEndDate(LocalDate.now().plusDays(30));

        assertThat(mapper.toDto(contract).getPaymentStatus()).isEqualTo("inProgress");
    }

    @Test
    void paymentStatus_derivation() {
        LocalDate today = LocalDate.of(2026, 9, 25);
        BigDecimal value = new BigDecimal("1000");

        assertThat(SubContractPaymentStatus.derive(value, null, null, today))
                .isEqualTo(SubContractPaymentStatus.NOT_STARTED);
        assertThat(SubContractPaymentStatus.derive(value, new BigDecimal("1"), null, today))
                .isEqualTo(SubContractPaymentStatus.IN_PROGRESS);
        assertThat(SubContractPaymentStatus.derive(value, new BigDecimal("1000"), today.minusDays(9), today))
                .isEqualTo(SubContractPaymentStatus.FULLY_PAID);
        assertThat(SubContractPaymentStatus.derive(value, new BigDecimal("999"), today.minusDays(1), today))
                .isEqualTo(SubContractPaymentStatus.OVERDUE);
        assertThat(SubContractPaymentStatus.derive(null, new BigDecimal("5"), today, today))
                .isEqualTo(SubContractPaymentStatus.IN_PROGRESS);
    }
}
