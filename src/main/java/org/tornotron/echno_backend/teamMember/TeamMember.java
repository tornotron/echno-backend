package org.tornotron.echno_backend.teamMember;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.project.Project;

@Entity
@Data
@NoArgsConstructor
@Table(name = "Team_member")
public class TeamMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id",nullable = false)
    private Long id;

    @NotBlank(message = "memberName is required")
    @Size(min = 3,max = 50,message = "memberName must be between 3 and 50 characters")
    @Column(name = "member_name",unique = true)
    private String memberName;

    @Column(unique = true,name = "member_email")
    private String memberEmail;

    @ManyToOne
    private Project project;
}
