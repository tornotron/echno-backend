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

    @Column(name = "member_phone",unique = true)
    private String memberPhone;

    @Column(name = "member_role")
    private String memberRole;

    @Column(name = "member_image")
    private String memberImage;

    @Column(name = "department")
    private String department;

    @Column(name = "designation")
    private String designation;

    @ManyToOne
    private Project project;

}
