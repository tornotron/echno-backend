package org.tornotron.echno_backend.vendor;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;

import java.util.ArrayList;
import java.util.List;


@Entity
@NoArgsConstructor
@Data
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vendor_name", nullable = false)
    private String vendorName;

    @Column(name = "vendor_address", nullable = true)
    private String vendorAddress;

    @Column(name = "vendor_email", nullable = false)
    private String vendorEmail;

    @OneToMany(mappedBy = "vendor")
    private List<GoodsReceivedNote> goodsReceivedNotes = new ArrayList<>();

    @OneToMany(mappedBy = "vendor")
    private List<PurchaseOrder> purchaseOrders = new ArrayList<>();

    @OneToMany(mappedBy = "vendor")
    private List<Payable> payables = new ArrayList<>();
}
