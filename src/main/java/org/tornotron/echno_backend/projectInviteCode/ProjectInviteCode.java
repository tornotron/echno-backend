package org.tornotron.echno_backend.projectInviteCode;

import jakarta.persistence.*;
import lombok.Data;
import org.tornotron.echno_backend.project.Project;

import java.time.LocalDateTime;

@Entity
@Data
public class ProjectInviteCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false,unique = true)
    private String code;

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

}
