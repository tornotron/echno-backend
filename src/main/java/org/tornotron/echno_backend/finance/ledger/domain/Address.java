package org.tornotron.echno_backend.finance.ledger.domain;


import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class Address {

    @Column(name = "address_line1", length = 200)
    private String line1;

    @Column(name = "address_line2", length = 200)
    private String line2;

    @Column(name = "address_city",  length = 100)
    private String city;

    @Column(name = "address_state", length = 100)
    private String state;

    @Column(name = "address_state_code", length = 2)
    private String stateCode;

    @Column(name = "address_postal_code", length = 20)
    private String postalCode;

    @Column(name = "address_country", length = 2)
    private String country;

}