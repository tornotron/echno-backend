package org.tornotron.echno_backend.modules.bim.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedFloor;

/** Basements count down from -1, codes are sibling-safe, furniture never becomes an element. */
class BimHierarchyProposalBuilderTest {

    @Test
    void storeysBelowGradeGetNegativeIndicesAndCodesAreSafeAndUnique() {
        Map<String, Object> structure = Map.of("sites", List.of(Map.of("buildings", List.of(Map.of(
                "globalId", "BLDG", "name", "Block A / North",
                "storeys", List.of(
                        Map.of("globalId", "S0", "name", "Ground", "elevation", 0.0, "spaces", List.of()),
                        Map.of("globalId", "SB2", "name", "Basement 2", "elevation", -6.0, "spaces", List.of()),
                        Map.of("globalId", "SB1", "name", "Basement 1", "elevation", -3.0, "spaces", List.of()),
                        Map.of("globalId", "S1", "name", "Ground", "elevation", 3.0, "spaces", List.of(
                                Map.of("globalId", "SP1", "name", "Lobby"),
                                Map.of("globalId", "SP2", "name", "Ground 2")))))))));
        UUID known = UUID.randomUUID();
        List<BimElement> elements = List.of(
                element("W1", "IfcWallStandardCase", "Wall A", "S1", "SP1"),
                element("W2", "IfcWallStandardCase", "Wall A", "S1", "SP1"),
                element("R1", "IfcRailing", "Rail", "S1", null),
                element("CH", "IfcFurnishingElement", "Chair", "S1", "SP1"),
                element("X1", "IfcColumn", "Orphan", null, null),
                element("X2", "IfcColumn", "Ghost storey", "S-NOT-IN-STRUCTURE", null),
                element("B1", "IfcBeam", "Beam", "S1", null));


        BimHierarchyProposalDto proposal = BimHierarchyProposalBuilder.build(UUID.randomUUID(), structure, elements,
                guid -> "SP1".equals(guid) ? known : null);

        assertThat(proposal.buildings()).singleElement().satisfies(b -> {
            assertThat(b.code()).isEqualTo("Block-A-North");
            assertThat(b.floors()).extracting(ProposedFloor::levelIndex).containsExactly(-2, -1, 0, 1);
            assertThat(b.floors()).extracting(ProposedFloor::code).containsExactly("Basement-2", "Basement-1", "Ground", "Ground-2");
            ProposedFloor first = b.floors().get(3);
            assertThat(first.zones()).hasSize(3);
            assertThat(first.zones()).extracting(z -> z.code())
                    .as("the default zone's code never collides with a real space's")
                    .containsExactly("Lobby", "Ground-2", "Ground-2-2");
            assertThat(first.zones().get(0).matchedNodeId()).isEqualTo(known);
            assertThat(first.zones().get(0).elements()).extracting(e -> e.code()).containsExactly("Wall-A", "Wall-A-2");
            assertThat(first.zones().get(0).elements()).extracting(e -> e.elementType()).containsOnly("wall");
            assertThat(first.zones().get(2).defaultZone()).isTrue();
            assertThat(first.zones().get(2).elements()).extracting(e -> e.globalId()).containsExactly("B1", "R1");
            assertThat(first.zones().get(2).elements().get(1).elementType()).as("no catalogue slug for a railing").isNull();
        });
        assertThat(proposal.counts()).containsEntry("elements", 4)
                .as("no storey and an unknown storey both count as unplaced").containsEntry("unplaced", 2)
                .containsEntry("matched", 1);
    }

    private static BimElement element(String guid, String type, String name, String storey, String space) {
        BimElement e = new BimElement();
        e.setGlobalId(guid);
        e.setIfcType(type);
        e.setName(name);
        e.setStoreyGlobalId(storey);
        e.setSpaceGlobalId(space);
        return e;
    }
}
