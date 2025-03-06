package org.tornotron.echno_backend.teamMember;

import jakarta.persistence.*;
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

    @Column(name = "member_name",unique = true)
    private String memberName;

    @Column(unique = true,name = "member_email")
    private String memberEmail;

    @ManyToOne
    private Project project;

}
