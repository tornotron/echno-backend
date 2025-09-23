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

/**
 * Represents a user entity in the system.
 * This class is mapped to the "Users_table" table in the database.
 */
@Entity
@Data
@Table(name = "Users_table")
public class User {

    /** The unique identifier for the user. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** The full name of the user. */
    @Column(name = "name", nullable = false)
    private String name;

    /** The gender of the user. */
    @Column(name = "gender", nullable = false)
    private String gender;

    /** The physical address of the user. */
    @Column(name = "address", nullable = true)
    private String address;

    /** The blood group of the user. */
    @Column(name = "blood_group", nullable = true)
    private String bloodGroup;

    /** The email address of the user. Must be unique. */
    @Column(name = "email", nullable = false, unique = true)
    private String email;

    /** The phone number of the user. Must be unique. */
    @Column(name = "phone", nullable = false, unique = true)
    private String phone;

    /** The date of birth of the user. */
    @Column(name = "date_of_birth", nullable = false)
    private LocalDateTime dateOfBirth;

    /** The educational qualification of the user. */
    @Column(name = "qualification", nullable = false)
    private String qualification;

    /** A list of skills possessed by the user. */
    @Column(name = "skills", nullable = true)
    private List<String> skills;

    /** The user's years of professional experience. */
    @Column(name = "experience", nullable = true)
    private Integer experience;

    /** The URL to the user's curriculum vitae (CV). */
    @Column(name = "cv_url", nullable = true)
    private String cvUrl;

    /** The contact information for use in an emergency. */
    @Column(name = "emergency_contact", nullable = true)
    private String emergencyContact;

    /** The role of the user in the system. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    /** The URL to the user's profile picture. */
    @Column(name = "profile_picture_url", nullable = true)
    private String profilePictureUrl;

    /** The timestamp when the user was created. Automatically generated. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** The timestamp when the user was last updated. Automatically generated. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** The list of employee records associated with this user. */
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Employee> employees = new ArrayList<>();

    /** The list of indents created by this user. */
    @OneToMany(mappedBy = "createdBy")
    private List<Intend> intends = new ArrayList<>();

    /** The list of goods received notes where this user is the receiver. */
    @OneToMany(mappedBy = "receivedBy")
    private List<GoodsReceivedNote> goodsReceivedNotes = new ArrayList<>();

    /** The list of inventory transactions created by this user. */
    @OneToMany(mappedBy = "createdBy")
    private List<InventoryTransaction> inventoryTransactions = new ArrayList<>();

    /** The list of material consumptions created by this user. */
    @OneToMany(mappedBy = "createdBy")
    private List<MaterialConsumption> materialConsumptions = new ArrayList<>();

    /** The list of payables created by this user. */
    @OneToMany(mappedBy = "createdBy")
    private List<Payable> payables = new ArrayList<>();

    /** The list of site transfers initiated by this user. */
    @OneToMany(mappedBy = "sendingPerson")
    private List<SiteTransfer> siteTransfers = new ArrayList<>();

}