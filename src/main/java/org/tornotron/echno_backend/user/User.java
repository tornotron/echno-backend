package org.tornotron.echno_backend.user;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.intend.Intend;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@Table(name = "Users_table")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "gender", nullable = false)
    private String gender;

    @Column(name = "blood_group", nullable = true)
    private String bloodGroup;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "phone", nullable = false, unique = true)
    private String phone;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDateTime dateOfBirth;

    @Column(name = "qualification", nullable = false)
    private String qualification;

    @Column(name = "skills", nullable = true)
    private List<String> skills;

    @Column(name = "experience", nullable = true)
    private Integer experience;

    @Column(name = "cv_url", nullable = true)
    private String cvUrl;

    @Column(name = "emergency_contact", nullable = true)
    private String emergencyContact;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @Column(name = "profile_picture_url", nullable = true)
    private String profilePictureUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Employee> employees = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<Intend> intends = new ArrayList<>();

    @OneToMany(mappedBy = "receivedBy")
    private List<GoodsReceivedNote> goodsReceivedNotes = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<InventoryTransaction> inventoryTransactions = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<MaterialConsumption> materialConsumptions = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<Payable> payables = new ArrayList<>();

    @OneToMany(mappedBy = "sendingPerson")
    private List<SiteTransfer> siteTransfers = new ArrayList<>();

}
