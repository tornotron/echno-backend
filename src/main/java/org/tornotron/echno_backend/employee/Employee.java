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

@Entity
@Data
@NoArgsConstructor
@Table(name = "Employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "designation", nullable = false)
    private String designation;

    @Column(name = "department", nullable = false)
    private String department;

    @Column(name = "joining_date", nullable = true)
    private LocalDateTime joiningDate;

    @Column(name = "salary", nullable = true)
    private Double salary;

    @Column(name = "reporting_manager", nullable = true)
    private String reportingManager;

    @Column(name = "shift_timing", nullable = true)
    private String shiftTiming;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EmployeeStatus status;

//    @Column(name = "certifications", nullable = true)
//    private List<String> certifications;


    @Column(name = "employee_name",nullable = false)
    private String employeeName;

    @Column(name = "gender",nullable = false)
    private String gender;

    @Column(name = "phone_number",nullable = false)
    private String phoneNumber;

    @Column(name = "email_address",nullable = false,unique = true)
    private String emailAddress;

    @Column(name = "date_of_birth",nullable = false)
    private LocalDateTime dateOfBirth;

    @OneToMany(mappedBy = "creator")
    private List<Task> tasks;

    @ManyToMany(mappedBy = "assignees")
    private List<Task> assignedTasks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
