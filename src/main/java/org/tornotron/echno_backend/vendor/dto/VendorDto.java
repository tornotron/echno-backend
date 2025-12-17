package org.tornotron.echno_backend.vendor.dto;

import lombok.Data;

@Data
public class VendorDto {

    private Long id;
    private String vendorName;
    private String vendorAddress;
    private String vendorEmail;
}
