package org.tornotron.echno_backend.vendor;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;

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
    private String vendorContact;

    @Column(name = "vendor_email", nullable = false)
    private String gstNumber;

    @OneToMany(mappedBy = "vendor")
    private List<GoodsReceivedNote> goodsReceivedNotes = new ArrayList<>();
}
