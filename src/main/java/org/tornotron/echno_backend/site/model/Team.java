package org.tornotron.echno_backend.site.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "teamName is required")
    @Size(min = 3,max = 50,message = "teamName must be between 3 and 50 characters")
    private String teamName;

    @ManyToOne
    private Project project;

    @OneToMany(mappedBy = "team")
    private List<TeamMember> members;
}
