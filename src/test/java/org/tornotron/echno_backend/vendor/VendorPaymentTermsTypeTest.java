package org.tornotron.echno_backend.vendor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.vendor.dto.VendorPaymentTermsCreationDto;
import org.tornotron.echno_backend.vendor.enums.VendorPaymentTermsType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vendor payment terms are held to echno-core's PaymentTerms values (#863). The column took any
 * string, so a value such as NET45, which the web cannot label, was stored.
 */
class VendorPaymentTermsTypeTest {

    @Test
    void acceptsCoresValues() {
        for (String value : new String[] {"IMMEDIATE", "NET15", "NET20", "NET30", "NET60", "NET90"}) {
            assertThat(VendorPaymentTermsType.requireValid(value)).isEqualTo(value);
        }
    }

    @Test
    void refusesAnythingElse() {
        assertThatThrownBy(() -> VendorPaymentTermsType.requireValid("NET45"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("NET30");
        assertThatThrownBy(() -> VendorPaymentTermsType.requireValid("net30"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void settingTermsOnAVendor_refusesAValueOutsideTheEnum() {
        VendorRepository vendorRepository = mock(VendorRepository.class);
        VendorPaymentTermsRepository termsRepository = mock(VendorPaymentTermsRepository.class);
        VendorSubEntityService service = new VendorSubEntityService(vendorRepository, null, null, null,
                termsRepository, null, null, null, null);
        TenantContext.setCurrentOrgId(1L);
        Vendor vendor = new Vendor();
        when(vendorRepository.findByIdAndOrganization_Id(5L, 1L)).thenReturn(Optional.of(vendor));
        VendorPaymentTerms existing = new VendorPaymentTerms();
        existing.setPaymentTerms("NET30");
        when(termsRepository.findByVendor_Id(5L)).thenReturn(Optional.of(existing));
        VendorPaymentTermsCreationDto dto = new VendorPaymentTermsCreationDto();
        dto.setPaymentTerms("NET45");

        assertThatThrownBy(() -> service.setPaymentTerms(5L, dto))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(existing.getPaymentTerms()).isEqualTo("NET30");
        verify(termsRepository, never()).save(any());
    }
}
