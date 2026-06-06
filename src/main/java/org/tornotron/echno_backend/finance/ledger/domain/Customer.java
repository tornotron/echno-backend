package org.tornotron.echno_backend.finance.ledger.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "customers",
        uniqueConstraints = @UniqueConstraint(name = "uk_customers_code", columnNames = "code"),
        indexes = {
            @Index(name = "idx_customer_name", columnList = "name"),
            @Index(name = "idx_customer_gstin", columnList = "gstin")
        })
@Getter
@Setter
@NoArgsConstructor
public class Customer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 15)
    @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
            message = "Invalid GSTIN format")
    private String gstin;

    @Column(length = 10)
    private String pan;

    @Column(length = 200)
    private String email;

    @Column(length = 20)
    private String phone;

    @Embedded
    private Address billingAddress = new Address();

    @Column(name = "credit_limit", precision = 19, scale = 4)
    private java.math.BigDecimal creditLimit;

    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays = 30;

    @Column(nullable = false)
    private boolean active = true;
}
