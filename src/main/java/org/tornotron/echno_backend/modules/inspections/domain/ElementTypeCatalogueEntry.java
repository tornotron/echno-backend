package org.tornotron.echno_backend.modules.inspections.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.common.module.GlobalReferenceData;

/**
 * The product-shipped catalogue of construction element types (column, beam, slab, door,
 * sprinkler-branch). Global reference data seeded by Liquibase, copied per organization into
 * {@link OrgElementType} exactly as the trade catalogue is. A spatial node of level ELEMENT
 * names one of the org's codes in its {@code element_type} slug.
 */
@GlobalReferenceData("Seeded element type catalogue shipped with the product; every organization copies from the same list and none owns a row")
@Entity
@Table(name = "element_type_catalogue")
@Getter @Setter
@NoArgsConstructor
public class ElementTypeCatalogueEntry {

    @Id
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "group_code", nullable = false, length = 50)
    private String groupCode;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
