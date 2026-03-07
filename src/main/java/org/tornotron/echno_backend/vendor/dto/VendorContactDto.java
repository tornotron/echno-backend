package org.tornotron.echno_backend.vendor.dto;

import lombok.Data;

@Data
public class VendorContactDto {
    private Long id;
    private String contactPerson;
    private String email;
    private String phone;
    private String alternatePhone;
    private boolean isPrimary;
}
