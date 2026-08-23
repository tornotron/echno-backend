package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "A postal address, used for customer billing.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddressDto {

    @Schema(description = "First address line.", example = "12 Marina Boulevard")
    @Size(max = 200,message = "Address line 1 cannot exceed 200 characters")
    private String line1;

    @Schema(description = "Second address line.", example = "Suite 400")
    @Size(max = 200,message = "Address line 2 cannot exceed 200 characters")
    private String line2;

    @Schema(description = "City.", example = "Chennai")
    @Size(max = 100,message = "City cannot exceed 100 characters")
    private String city;

    @Schema(description = "State or province.", example = "Tamil Nadu")
    @Size(max = 100,message = "State cannot exceed 100 characters")
    private String state;

    @Schema(description = "GST state code.", example = "33")
    @Size(max = 2,message = "State code cannot exceed 2 characters")
    private String stateCode;

    @Schema(description = "Postal or PIN code.", example = "600001")
    @Size(max = 20,message = "Postal code cannot exceed 20 characters")
    private String postalCode;

    @Schema(description = "ISO 3166-1 alpha-2 country code.", example = "IN")
    @Size(max = 2,message = "Country code cannot exceed 2 characters")
    private String country;
}
