package org.tornotron.echno_backend.vendor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.vendor.dto.VendorTaxIdentifierCreationDto;
import org.tornotron.echno_backend.vendor.enums.TaxIdentifierType;
import org.tornotron.echno_backend.vendor.mapper.VendorTaxIdentifierMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The tax identifier types the vendor form offers are types the backend accepts (#874). The form
 * offers GST, PAN, TAN, TIN, CIN and OTHER; the last four used to answer 400.
 */
class VendorTaxIdentifierTypeTest {

    private VendorSubEntityService service;
    private Vendor vendor;

    @BeforeEach
    void setUp() {
        VendorRepository vendorRepository = mock(VendorRepository.class);
        service = new VendorSubEntityService(vendorRepository, null, null, null, null, null,
                mock(VendorTaxIdentifierMapper.class), null, null);
        TenantContext.setCurrentOrgId(1L);
        vendor = new Vendor();
        vendor.setOrganization(new Organization());
        when(vendorRepository.findByIdAndOrganization_Id(5L, 1L)).thenReturn(Optional.of(vendor));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GST", "PAN", "TAN", "TIN", "CIN", "OTHER"})
    void everyTypeTheFormOffersIsSaved(String type) {
        service.addTaxIdentifier(5L, request(type));

        assertThat(vendor.getTaxIdentifiers()).singleElement()
                .extracting(VendorTaxIdentifier::getType)
                .isEqualTo(TaxIdentifierType.valueOf(type));
    }

    @Test
    void aTypeNobodyDefinedIsStillRefused() {
        assertThatThrownBy(() -> service.addTaxIdentifier(5L, request("ABC")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static VendorTaxIdentifierCreationDto request(String type) {
        VendorTaxIdentifierCreationDto dto = new VendorTaxIdentifierCreationDto();
        dto.setType(type);
        dto.setValue("VALUE-1");
        return dto;
    }
}
