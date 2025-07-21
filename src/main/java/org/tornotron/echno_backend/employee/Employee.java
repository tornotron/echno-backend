package org.tornotron.echno_backend.employee;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.task.Task;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@Table(name = "Employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
}
