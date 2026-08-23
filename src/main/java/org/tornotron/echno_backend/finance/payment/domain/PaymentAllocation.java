package org.tornotron.echno_backend.finance.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The portion of a payment applied to one specific invoice.
 *
 * <p>Links a payment to an invoice and records how much of the payment settles that invoice. The
 * allocations of a payment sum to its total amount, and each is capped at the invoice's outstanding
 * balance.
 */
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
