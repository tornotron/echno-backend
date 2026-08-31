package org.tornotron.echno_backend.category;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.task.Task;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;

/**
 * Represents a category entity in the system, used for classifying tasks.
 * This class is mapped to the "Category" table in the database.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "Category")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Category implements TenantScopedEntity {

    /** The unique identifier for the category. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The name of the category. It must be unique. */
    @Column(name = "category_name", nullable = false)
    private String name;

    /**
     * The name with case, punctuation and spacing folded away, used to decide whether a category
     * already exists. Unique per organization rather than globally: the constraint that enforces
     * it is {@code uk_category_org_normalized_name} in the schema, and it is composite, so it
     * cannot be declared here as a column-level unique.
     */
    @Column(name = "normalized_name")
    private String normalizedName;

    /** A brief description of the category. */
    @Column(name = "category_description")
    private String description;

    /** The URL or path to an icon representing the category. */
    @Column(name = "category_icon")
    private String icon;

    /** The URL or path to an image representing the category. */
    @Column(name = "category_image")
    private String image;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    /** The list of tasks associated with this category. */
    @OneToMany(mappedBy = "category")
    private List<Task> tasks;
}