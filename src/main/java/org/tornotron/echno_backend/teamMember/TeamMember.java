package org.tornotron.echno_backend.teamMember;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.project.Project;

@Entity
@Data
@NoArgsConstructor
public class TeamMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "memberName is required")
    @Size(min = 3,max = 50,message = "memberName must be between 3 and 50 characters")
    @Column(unique = true)
    private String memberName;

    @Column(unique = true)
    private String memberEmail;

    @ManyToOne
    private Project project;
}
