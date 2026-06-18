package org.tornotron.echno_backend.finance.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "payment_allocations",
        indexes = {
                @Index(name = "idx_palloc_payment", columnList = "payment_id"),
                @Index(name = "idx_palloc_invoice", columnList = "invoice_id")
        })
@Getter @Setter
@NoArgsConstructor
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount;
}
