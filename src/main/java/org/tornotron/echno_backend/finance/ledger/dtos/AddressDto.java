package org.tornotron.echno_backend.finance.ledger.dtos;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddressDto {

    @Size(max = 200,message = "Address line 1 cannot exceed 200 characters")
    private String line1;

    @Size(max = 200,message = "Address line 2 cannot exceed 200 characters")
    private String line2;

    @Size(max = 100,message = "City cannot exceed 100 characters")
    private String city;

    @Size(max = 100,message = "State cannot exceed 100 characters")
    private String state;

    @Size(max = 2,message = "State code cannot exceed 2 characters")
    private String stateCode;

    @Size(max = 20,message = "Postal code cannot exceed 20 characters")
    private String postalCode;

    @Size(max = 2,message = "Country code cannot exceed 2 characters")
    private String country;
}
