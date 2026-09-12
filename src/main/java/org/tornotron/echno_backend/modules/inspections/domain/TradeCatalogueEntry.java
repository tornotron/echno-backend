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
 * The product-shipped catalogue of inspection trades. Global reference data seeded by
 * Liquibase: every organization copies from the same list and none owns a row. The code is
 * the stable key and, for the sixteen trades that predate the catalogue, is the same slug the
 * {@code InspectionTrade} enum has always put on the wire.
 *
 * <p>Tenant rows never reference this table by id. An organization works against its own
 * {@link OrgTrade} copy, made by {@code TradeService.ensureOrgTrades}, and the copy remembers
 * its origin through {@code catalogueCode}.
 */
@GlobalReferenceData("Seeded trade catalogue shipped with the product; every organization copies from the same list and none owns a row")
@Entity
@Table(name = "trade_catalogue")
@Getter @Setter
@NoArgsConstructor
public class TradeCatalogueEntry {

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
