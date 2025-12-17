package org.tornotron.echno_backend.payable;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.payable.enums.ContractType;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.vendor.Vendor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@NoArgsConstructor
public class Payable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payable_number", nullable = false, unique = true)
    private String payableNumber;

    @Column(name = "contactor_name", nullable = false)
    private String contractorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type")
    private ContractType contractType;

    @Column(name = "amount_recorded", precision = 15, scale = 2)
    private BigDecimal amountRecorded;

    @Column(name = "amount_paid", precision = 15, scale = 2)
    private BigDecimal amountPaid;

    public BigDecimal getAmountDue() {
        return (amountRecorded == null ? BigDecimal.ZERO : amountRecorded)
                .subtract(amountPaid == null ? BigDecimal.ZERO : amountPaid);
    }

    @ManyToOne
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    @ManyToOne
    @JoinColumn(name = "goods_received_note_id")
    private GoodsReceivedNote goodsReceivedNote;

    @ManyToOne
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
