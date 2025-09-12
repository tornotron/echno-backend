package org.tornotron.echno_backend.inventoryTransaction;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;

@Data
@Entity
@NoArgsConstructor
public class InventoryTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @ManyToOne
    private Material material;

    @Column(name = "opening_stock")
    private Integer openingStock;

    @Column(name = "quantity_changed")
    private Integer quantityChanged;

    @Column(name = "closing_stock")
    private Integer closingStock;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private InventoryTransactionType transactionType;

    @Column(name = "reference_number",nullable = false)
    private String referenceNumber;

    private String remarks;

    @ManyToOne
    private User createdBy;
}
