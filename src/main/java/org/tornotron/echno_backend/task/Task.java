package org.tornotron.echno_backend.task;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    @NotBlank(message = "taskName is required")
    @Size(min = 3,max = 50,message = "taskName must be between 3 and 50 characters")
    private String taskName;

    @Column(name = "categories")
    @NotBlank(message = "categories is required")
    @Size(min = 3,max = 50,message = "categories must be between 3 and 50 characters")
    private String categories;

    @Column(nullable = true,name = "photo")
    @Lob
    private byte[] photo;

    @Column(nullable = true)
    private Integer progress;

}
