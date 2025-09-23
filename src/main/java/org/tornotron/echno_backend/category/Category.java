package org.tornotron.echno_backend.category;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.task.Task;

import java.util.List;

/**
 * Represents a category entity in the system, used for classifying tasks.
 * This class is mapped to the "Category" table in the database.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "Category")
public class Category {

    /** The unique identifier for the category. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The name of the category. It must be unique. */
    @Column(name = "category_name", unique = true, nullable = false)
    private String name;

    /** A brief description of the category. */
    @Column(name = "category_description")
    private String description;

    /** The URL or path to an icon representing the category. */
    @Column(name = "category_icon")
    private String icon;

    /** The URL or path to an image representing the category. */
    @Column(name = "category_image")
    private String image;

    /** The list of tasks associated with this category. */
    @OneToMany(mappedBy = "category")
    private List<Task> tasks;
}