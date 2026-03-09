package org.tornotron.echno_backend.employee;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.intend.Intend;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.user.User;

import org.tornotron.echno_backend.common.enums.OrgRole;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents an employee entity in the system.
 * This class links a {@link User} to an {@link Organization} and stores employment-specific details.
 * It is mapped to the "Employee" table in the database.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "Employee")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Employee implements TenantScopedEntity {

    /** The unique identifier for the employee record. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** The organization-specific identifier for the employee. */
    @Column(name = "employee_id")
    private String employeeId;

    /** The employee's job title or designation. */
    @Column(name = "designation", nullable = true)
    private String designation;

    /** The department the employee works in. */
    @Column(name = "department", nullable = true)
    private String department;

    /** The date and time the employee joined the organization. */
    @Column(name = "joining_date", nullable = true)
    private LocalDateTime joiningDate;

    /** The employee's salary. */
    @Column(name = "salary", nullable = true)
    private Double salary;

    /** The employee's reporting manager. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Employee manager;

    /** The employee's work shift schedule. */
    @Column(name = "shift_timing", nullable = true)
    private String shiftTiming;

    /** The current employment status of the employee. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = true)
    private EmployeeStatus status;

//    @Column(name = "certifications", nullable = true)
//    private List<String> certifications;


    /** The name of the employee. */
    @Column(name = "employee_name",nullable = false)
    private String employeeName;

    /** The gender of the employee. */
    @Column(name = "gender",nullable = false)
    private String gender;

    /** The contact phone number of the employee. */
    @Column(name = "phone_number",nullable = false)
    private String phoneNumber;

    /** The physical address of the employee. */
    @Column(name = "address")
    private String address;

    /** The contact email address of the employee. */
    @Column(name = "email_address",nullable = false)
    private String emailAddress;

    /** The date of birth of the employee. */
    @Column(name = "date_of_birth",nullable = false)
    private LocalDateTime dateOfBirth;

    @Column(name = "is_manager", nullable = false)
    private boolean isManager = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "employee_org_roles", joinColumns = @JoinColumn(name = "employee_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<OrgRole> orgRoles = new HashSet<>();

    /** The list of projects this employee is assigned to. */
    @ManyToMany(mappedBy = "employees")
    private List<Project> projects = new ArrayList<>();

    /** The list of tasks created by this employee. */
    @OneToMany(mappedBy = "creator")
    private List<Task> tasks;

    /** The list of tasks assigned to this employee. */
    @ManyToMany(mappedBy = "assignees")
    private List<Task> assignedTasks;

    /** The organization this employee belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** The user account associated with this employee record. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "createdBy", fetch = FetchType.LAZY)
    private List<Issue> createdIssues = new ArrayList<>();

    @OneToMany(mappedBy = "assignedTo", fetch = FetchType.LAZY)
    private List<Issue> assignedIssues = new ArrayList<>();

    @OneToMany(mappedBy = "receivedBy")
    private List<GoodsReceivedNote> goodsReceivedNotes = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<Intend> intends = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<PurchaseOrder> purchaseOrders = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<Payable> payables = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<InventoryTransaction> inventoryTransactions = new ArrayList<>();

    @OneToMany(mappedBy = "createdBy")
    private List<Material> materials = new ArrayList<>();


}
