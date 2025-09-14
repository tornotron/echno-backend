package org.tornotron.echno_backend.projectInviteCode;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.project.Project;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Data
public class ProjectInviteCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false,unique = true)
    private int code;

    @ManyToOne
    private Project project;

    @Column(nullable = false)
    private LocalDateTime expiryDate;

    @Column(nullable = false)
    private boolean isActive;

    @Column(nullable = false)
    private int maxUses;

    @Column(nullable = false)
    private int currentUses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> employeeDetails;

}
