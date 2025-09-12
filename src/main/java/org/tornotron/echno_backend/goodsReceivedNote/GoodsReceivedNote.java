package org.tornotron.echno_backend.goodsReceivedNote;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.vendor.Vendor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
public class GoodsReceivedNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grn_number", nullable = false, unique = true)
    private String grnNumber;

    @Column(name = "received_on", nullable = false)
    private LocalDateTime receivedOn;

    @ManyToOne
    private User receivedBy;

    @ManyToOne
    private Vendor vendor;

    @Column(name = "delivery_challan_number")
    private String deliveryChallanNumber;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "invoice_amount")
    private Double invoiceAmount;

    @OneToMany(mappedBy = "goodsReceivedNote")
    private List<GrnItem> items = new ArrayList<>();


}
