package org.tornotron.echno_backend.category;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.task.Task;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@Table(name = "Category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "category_name", unique = true, nullable = false)
    private String name;

    @Column(name = "category_description")
    private String description;

    @Column(name = "category_icon")
    private String icon;

    @Column(name = "category_image")
    private String image;

    @OneToMany(mappedBy = "category")
    private List<Task> tasks;
}
