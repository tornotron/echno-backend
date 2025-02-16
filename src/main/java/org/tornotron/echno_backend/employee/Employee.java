package org.tornotron.echno_backend.employee;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    @Column(name = "email_address",nullable = false)
    private String emailAddress;

    @Column(name = "date_of_birth",nullable = false)
    private LocalDateTime dateOfBirth;

}
