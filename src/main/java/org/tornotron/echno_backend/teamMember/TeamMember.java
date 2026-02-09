package org.tornotron.echno_backend.teamMember;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.project.Project;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

@Entity
@Data
@NoArgsConstructor
@Table(name = "Team_member")
@FilterDef(name = "orgFilter", parameters = @ParamDef(name = "organizationId", type = Long.class))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class TeamMember implements TenantScopedEntity {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

}
