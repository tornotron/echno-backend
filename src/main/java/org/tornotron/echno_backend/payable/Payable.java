package org.tornotron.echno_backend.payable;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.lang.Contract;
import org.tornotron.echno_backend.payable.enums.ContractType;
import org.tornotron.echno_backend.user.User;

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

    @Column(name = "amount_recorded")
    private BigDecimal amountRecorded;

    @Column(name = "amount_paid")
    private BigDecimal amountPaid;

    public BigDecimal getAmountDue() {
        return (amountRecorded == null ? BigDecimal.ZERO : amountRecorded)
                .subtract(amountPaid == null ? BigDecimal.ZERO : amountPaid);
    }

    @ManyToOne
    private User createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
