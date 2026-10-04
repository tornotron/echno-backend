package org.tornotron.echno_backend.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * A sub-category under one work category, such as Excavation under Earthwork. It is what the
 * second dropdown on the task form offers once a category is chosen.
 *
 * <p>Per organization, like the category it sits under, and seeded the same way: the standard list
 * for every new organization by {@link WorkCategorySeeder}, and changeset
 * {@code 130-backfill-standard-work-subcategories} for organizations that existed before. A task
 * does not reference this row. It keeps the sub-category as text, because the form also takes a
 * sub-category typed in by hand, so this list is the set of suggestions and nothing more.
 */
@Entity
@Table(name = "work_subcategory", uniqueConstraints = {
        @UniqueConstraint(name = "uq_work_subcategory_category_name", columnNames = {"category_id", "normalized_name"})
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class WorkSubcategory implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "name", nullable = false)
    private String name;

    /** The name folded by {@link CategoryNormalizer}, the key a rerun of the seed dedupes on. */
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Column(name = "description", length = 500)
    private String description;

    /** Position in the dropdown, the order of the supplied list. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
