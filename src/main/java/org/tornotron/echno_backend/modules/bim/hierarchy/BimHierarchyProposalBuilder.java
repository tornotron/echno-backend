package org.tornotron.echno_backend.modules.bim.hierarchy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedBuilding;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedElement;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedFloor;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedZone;

/**
 * Turns the worker's {@code structure.json} and the model's live elements into a proposal.
 * Pure: no database, no side effects. Matching against existing nodes is done through the
 * lookup handed in, so the same builder serves the proposal and a later refresh.
 */
final class BimHierarchyProposalBuilder {

    static final int CODE_MAX = 50;

    private BimHierarchyProposalBuilder() {
    }

    @SuppressWarnings("unchecked")
    static BimHierarchyProposalDto build(UUID versionId, Map<String, Object> structure, List<BimElement> elements,
                                         Function<String, UUID> nodeByGuid) {
        Map<String, List<BimElement>> byStorey = new HashMap<>();
        int unplaced = 0;
        for (BimElement e : elements) {
            if (e.isRetired() || !BimIfcTypes.isConstructionElement(e.getIfcType())) {
                continue;
            }
            if (e.getStoreyGlobalId() == null) {
                unplaced++;
                continue;
            }
            byStorey.computeIfAbsent(e.getStoreyGlobalId(), k -> new ArrayList<>()).add(e);
        }

        int[] counts = new int[5];
        Set<String> storeysSeen = new HashSet<>();
        List<ProposedBuilding> buildings = new ArrayList<>();
        Set<String> buildingCodes = new HashSet<>();
        int buildingNo = 0;
        for (Map<String, Object> site : listOf(structure.get("sites"))) {
            for (Map<String, Object> building : listOf(site.get("buildings"))) {
                buildingNo++;
                String guid = str(building.get("globalId"));
                String name = firstNonBlank(str(building.get("name")), "Building " + buildingNo);
                String code = unique(codeFrom(name, "B" + buildingNo), buildingCodes);
                UUID matched = match(nodeByGuid, guid, counts);
                buildings.add(new ProposedBuilding(guid, name, code, matched,
                        floors(building, byStorey, storeysSeen, nodeByGuid, counts)));
                counts[0]++;
            }
        }
        // Elements that name a storey the structure does not have are as unplaced as those with none.
        for (Map.Entry<String, List<BimElement>> entry : byStorey.entrySet()) {
            if (!storeysSeen.contains(entry.getKey())) {
                unplaced += entry.getValue().size();
            }
        }
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("buildings", counts[0]);
        summary.put("floors", counts[1]);
        summary.put("zones", counts[2]);
        summary.put("elements", counts[3]);
        summary.put("matched", counts[4]);
        summary.put("unplaced", unplaced);
        return new BimHierarchyProposalDto(versionId, LocalDateTime.now(), null, buildings, summary, null);
    }

    private static List<ProposedFloor> floors(Map<String, Object> building, Map<String, List<BimElement>> byStorey,
                                              Set<String> storeysSeen, Function<String, UUID> nodeByGuid, int[] counts) {
        List<Map<String, Object>> storeys = new ArrayList<>(listOf(building.get("storeys")));
        storeys.sort(Comparator.comparing(s -> dbl(s.get("elevation")) == null ? Double.MAX_VALUE : dbl(s.get("elevation"))));
        int negatives = (int) storeys.stream().filter(s -> dbl(s.get("elevation")) != null && dbl(s.get("elevation")) < 0).count();
        List<ProposedFloor> floors = new ArrayList<>();
        Set<String> floorCodes = new HashSet<>();
        for (int i = 0; i < storeys.size(); i++) {
            Map<String, Object> storey = storeys.get(i);
            String guid = str(storey.get("globalId"));
            storeysSeen.add(guid);
            int levelIndex = i - negatives;
            String name = firstNonBlank(str(storey.get("name")), "Level " + levelIndex);
            String code = unique(codeFrom(name, "L" + levelIndex), floorCodes);
            UUID matched = match(nodeByGuid, guid, counts);
            floors.add(new ProposedFloor(guid, name, code, levelIndex, dbl(storey.get("elevation")), matched,
                    zones(storey, code, byStorey.getOrDefault(guid, List.of()), nodeByGuid, counts)));
            counts[1]++;
        }
        return floors;
    }

    private static List<ProposedZone> zones(Map<String, Object> storey, String floorCode, List<BimElement> inStorey,
                                            Function<String, UUID> nodeByGuid, int[] counts) {
        Map<String, List<BimElement>> bySpace = new HashMap<>();
        List<BimElement> noSpace = new ArrayList<>();
        Set<String> spaceGuids = new HashSet<>();
        for (Map<String, Object> space : listOf(storey.get("spaces"))) {
            spaceGuids.add(str(space.get("globalId")));
        }
        for (BimElement e : inStorey) {
            if (e.getSpaceGlobalId() != null && spaceGuids.contains(e.getSpaceGlobalId())) {
                bySpace.computeIfAbsent(e.getSpaceGlobalId(), k -> new ArrayList<>()).add(e);
            } else {
                noSpace.add(e);
            }
        }
        List<ProposedZone> zones = new ArrayList<>();
        Set<String> zoneCodes = new HashSet<>();
        int zoneNo = 0;
        for (Map<String, Object> space : listOf(storey.get("spaces"))) {
            zoneNo++;
            String guid = str(space.get("globalId"));
            String name = firstNonBlank(str(space.get("name")), str(space.get("longName")), "Zone " + zoneNo);
            String code = unique(codeFrom(name, "Z" + zoneNo), zoneCodes);
            UUID matched = match(nodeByGuid, guid, counts);
            zones.add(new ProposedZone(guid, name, code, false, matched,
                    elements(bySpace.getOrDefault(guid, List.of()), nodeByGuid, counts)));
            counts[2]++;
        }
        if (!noSpace.isEmpty() || zones.isEmpty()) {
            String code = unique(floorCode, zoneCodes);
            zones.add(new ProposedZone(null, floorCode, code, true, null, elements(noSpace, nodeByGuid, counts)));
            counts[2]++;
        }
        return zones;
    }

    private static List<ProposedElement> elements(List<BimElement> rows, Function<String, UUID> nodeByGuid, int[] counts) {
        List<ProposedElement> out = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        List<BimElement> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(BimElement::getIfcType).thenComparing(e -> e.getName() == null ? "" : e.getName()));
        for (BimElement e : sorted) {
            String code = unique(codeFrom(e.getName(), e.getGlobalId()), codes);
            UUID matched = e.getSpatialNodeId() != null ? e.getSpatialNodeId() : match(nodeByGuid, e.getGlobalId(), counts);
            if (e.getSpatialNodeId() != null) {
                counts[4]++;
            }
            out.add(new ProposedElement(e.getGlobalId(), e.getIfcType(), e.getName(), code,
                    BimIfcTypes.elementTypeSlug(e.getIfcType()).orElse(null), matched));
            counts[3]++;
        }
        return out;
    }

    private static UUID match(Function<String, UUID> nodeByGuid, String guid, int[] counts) {
        if (guid == null) {
            return null;
        }
        UUID found = nodeByGuid.apply(guid);
        if (found != null) {
            counts[4]++;
        }
        return found;
    }

    /** A sibling code from a name: trimmed, whitespace to hyphens, odd characters dropped, capped. */
    static String codeFrom(String name, String fallback) {
        if (name == null || name.isBlank()) {
            return truncate(fallback);
        }
        String code = name.trim().replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9._:\\-]", "")
                .replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        return code.isBlank() ? truncate(fallback) : truncate(code);
    }

    private static String unique(String code, Set<String> taken) {
        String candidate = code;
        int n = 2;
        while (!taken.add(candidate)) {
            String suffix = "-" + n++;
            candidate = truncate(code.substring(0, Math.min(code.length(), CODE_MAX - suffix.length()))) + suffix;
        }
        return candidate;
    }

    private static String truncate(String s) {
        return s.length() <= CODE_MAX ? s : s.substring(0, CODE_MAX);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return values[values.length - 1];
    }
}
