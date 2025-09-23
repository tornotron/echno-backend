package org.tornotron.echno_backend.employee;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents an employee entity in the system.
 * This class links a {@link User} to an {@link Organization} and stores employment-specific details.
 * It is mapped to the "Employee" table in the database.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "Employee")
public class Employee {

    /** The unique identifier for the employee record. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** The employee's job title or designation. */
    @Column(name = "designation", nullable = false)
    private String designation;

    /** The department the employee works in. */
    @Column(name = "department", nullable = false)
    private String department;

    /** The date and time the employee joined the organization. */
    @Column(name = "joining_date", nullable = true)
    private LocalDateTime joiningDate;

    /** The employee's salary. */
    @Column(name = "salary", nullable = true)
    private Double salary;

    /** The name or ID of the employee's reporting manager. */
    @Column(name = "reporting_manager", nullable = true)
    private String reportingManager;

    /** The employee's work shift schedule. */
    @Column(name = "shift_timing", nullable = true)
    private String shiftTiming;

    /** The current employment status of the employee. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
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

    /** The contact email address of the employee. Must be unique. */
    @Column(name = "email_address",nullable = false,unique = true)
    private String emailAddress;

    /** The date of birth of the employee. */
    @Column(name = "date_of_birth",nullable = false)
    private LocalDateTime dateOfBirth;

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
}