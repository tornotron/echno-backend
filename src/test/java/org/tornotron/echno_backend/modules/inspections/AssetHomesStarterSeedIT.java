package org.tornotron.echno_backend.modules.inspections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Asset Homes starter seed of changeset 121 (#837), read back through the schema the
 * changelog built: fifteen templates with the source reference in their description, 256
 * items in total, the item counts and stage headings each sheet has, and one sampled item
 * per sheet at its position. The trades the seed added are in the catalogue, so the
 * foreign key on trade_code held, and a covered trade kept the id 057-03 or 106-01 gave it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AssetHomesStarterSeedIT extends AbstractIntegrationTest {

    private static final List<String> ASSET_HOMES_TRADES = List.of(
            "earthwork", "setting-out", "piling", "backfilling", "anti-termite", "shuttering-formwork",
            "reinforcement", "rcc", "masonry", "plastering", "waterproofing", "painting", "flooring",
            "doors-windows", "material-inspection");

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void everySheet_isAStarterWithItsSourceReference() {
        for (String trade : ASSET_HOMES_TRADES) {
            String description = (String) entityManager.createNativeQuery(
                            "SELECT description FROM starter_checklist_templates WHERE trade_code = :trade AND active")
                    .setParameter("trade", trade).getSingleResult();
            assertThat(description).as("starter for %s", trade).contains("Asset Homes").contains("AH/F/2201/");
        }
        assertThat(count("SELECT count(*) FROM starter_checklist_templates")).isEqualTo(27);
        assertThat(count("SELECT count(*) FROM starter_checklist_template_items i "
                + "JOIN starter_checklist_templates t ON t.id = i.template_id "
                + "WHERE t.description LIKE 'Asset Homes%'")).isEqualTo(256);
    }

    @Test
    void aCoveredTrade_keptTheIdOfTheStarterItReplaced() {
        // 057-03 (reinforcement) and 106-01 (painting): the row is updated in place
        assertThat(entityManager.createNativeQuery(
                "SELECT id FROM starter_checklist_templates WHERE trade_code = 'reinforcement'").getSingleResult())
                .isEqualTo(UUID.fromString("dab3b565-b360-5e4b-ac8f-241506758bef"));
        assertThat(entityManager.createNativeQuery(
                "SELECT id FROM starter_checklist_templates WHERE trade_code = 'painting'").getSingleResult())
                .isEqualTo(UUID.fromString("4161931e-4a6e-5e95-9e5e-0eaeee17736a"));
        // and none of the generic draft items survived alongside the new ones
        assertThat(count("SELECT count(*) FROM starter_checklist_template_items i "
                + "JOIN starter_checklist_templates t ON t.id = i.template_id "
                + "WHERE t.trade_code = 'reinforcement' AND i.check_point NOT LIKE '%: %'")).isZero();
    }

    @Test
    void theAddedTrades_areInTheCatalogueUnderTheirGroups() {
        for (String trade : List.of("earthwork", "setting-out", "piling", "backfilling", "anti-termite")) {
            assertThat(entityManager.createNativeQuery(
                    "SELECT group_code FROM trade_catalogue WHERE code = :code").setParameter("code", trade)
                    .getSingleResult()).as("group of %s", trade).isEqualTo("sitework");
        }
        assertThat(entityManager.createNativeQuery(
                "SELECT group_code FROM trade_catalogue WHERE code = 'material-inspection'").getSingleResult())
                .isEqualTo("general");
    }

    @ParameterizedTest(name = "{0}: {1} items, item {2} under {3}")
    @CsvSource(delimiter = '|', textBlock = """
            earthwork           | 20 | 1  | Before Excavation              | Site Inspection: Site Inspection done to evaluate existing conditions, obstructions including underground service lines if any?
            setting-out         | 13 | 4  | Before Setting Out             | Benchmarks: Have permanent benchmarks been established?
            piling              | 20 | 13 | During Piling                  | Density of Bentonite Slurry: Is the proper density of bentonite slurry being monitored and maintained for supporting the borehole and stabilizing the soil?
            backfilling         | 8  | 8  | After                          | Surplus Earth Disposal: Has the disposal of surplus earth from the work site been confirmed?
            anti-termite        | 13 | 1  | Pre-Treatment Checklist        | Pre-Inspection: Has the area been inspected to assess termite infestation?
            shuttering-formwork | 15 | 15 | After Shuttering               | Waste Disposal: Are waste materials being disposed of according to the environmental regulations?
            reinforcement       | 19 | 12 | During Reinforcement Placement | Proper Spacing and Cover Blocks: Is the spacing of bars and stirrups in adherence to the drawings, and are a sufficient number of cover blocks of specified thickness being used?
            rcc                 | 31 | 21 | During Concreting              | Cube and Slump Testing: Have the slump test conducted and the number of cubes been taken as per concrete quantity?
            masonry             | 22 | 5  | Before Masonry Work            | Provision of concrete kerb: Has concrete kerb been provided at required areas as per standard procedure?
            plastering          | 26 | 26 | After Plastering Work          | Waste Disposal: Is there proper disposal of waste materials according to environmental regulations?
            waterproofing       | 17 | 1  | Surface Preparation            | Surface Preparation: Have surfaces been ensured to be clean, dry, and free from contaminants and has patchwork been done on cavities using an approved repair mortar?
            painting            | 14 | 14 | After Painting Work            | Waste Disposal: Is the disposal of waste materials and paint cans being carried out in accordance with environmental regulations?
            flooring            | 15 | 1  | Before Tiling Work             | Base Surface Cleanliness: Has the base surface been verified to be clean and free from loose mortar and debris?
            doors-windows       | 12 | 9  | Installation and Fixing        | Silicon Sealant Gap: Has a 3 mm gap been provided all around the aluminium/UPVC joineries to accommodate silicon sealant?
            material-inspection | 11 | 11 | On receipt at site             | Check for any breakages/damages in the material
            """)
    void eachSheet_hasItsItemCountAndTheSampledItemAtItsPosition(String trade, int items, int serial,
                                                                 String category, String checkPoint) {
        assertThat(((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM starter_checklist_template_items i "
                                + "JOIN starter_checklist_templates t ON t.id = i.template_id WHERE t.trade_code = :trade")
                .setParameter("trade", trade).getSingleResult()).longValue())
                .as("items of %s", trade).isEqualTo(items);
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT i.category, i.check_point, i.priority, i.photos_required "
                                + "FROM starter_checklist_template_items i "
                                + "JOIN starter_checklist_templates t ON t.id = i.template_id "
                                + "WHERE t.trade_code = :trade AND i.line_order = :lineOrder")
                .setParameter("trade", trade).setParameter("lineOrder", serial - 1).getSingleResult();
        assertThat(row[0]).as("category of %s item %d", trade, serial).isEqualTo(category);
        assertThat(row[1]).as("check point of %s item %d", trade, serial).isEqualTo(checkPoint);
        assertThat(row[2]).isEqualTo("medium");
    }

    private long count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
