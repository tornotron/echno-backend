package org.tornotron.echno_backend.modules.bim.hierarchy;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which IfcProducts become construction elements, and the element type slug each maps to in
 * the inspection catalogue. The note's default list: walls, columns, beams, slabs, footings,
 * doors, windows, stairs, railings, roofs. Railings and roofs have no catalogue slug yet, so
 * they become elements with no type; configurable per organisation later.
 */
public final class BimIfcTypes {

    private static final Map<String, String> CONSTRUCTION = Map.ofEntries(
            Map.entry("IFCWALL", "wall"),
            Map.entry("IFCWALLSTANDARDCASE", "wall"),
            Map.entry("IFCWALLELEMENTEDCASE", "wall"),
            Map.entry("IFCCURTAINWALL", "wall"),
            Map.entry("IFCCOLUMN", "column"),
            Map.entry("IFCCOLUMNSTANDARDCASE", "column"),
            Map.entry("IFCBEAM", "beam"),
            Map.entry("IFCBEAMSTANDARDCASE", "beam"),
            Map.entry("IFCSLAB", "slab"),
            Map.entry("IFCSLABSTANDARDCASE", "slab"),
            Map.entry("IFCFOOTING", "footing"),
            Map.entry("IFCPILE", "footing"),
            Map.entry("IFCDOOR", "door"),
            Map.entry("IFCDOORSTANDARDCASE", "door"),
            Map.entry("IFCWINDOW", "window"),
            Map.entry("IFCWINDOWSTANDARDCASE", "window"),
            Map.entry("IFCSTAIR", "staircase"),
            Map.entry("IFCSTAIRFLIGHT", "staircase"),
            Map.entry("IFCRAILING", ""),
            Map.entry("IFCROOF", ""));

    private BimIfcTypes() {
    }

    public static boolean isConstructionElement(String ifcType) {
        return ifcType != null && CONSTRUCTION.containsKey(ifcType.trim().toUpperCase(Locale.ROOT));
    }

    /** The catalogue slug for a construction element type, empty when it has none. */
    public static Optional<String> elementTypeSlug(String ifcType) {
        if (ifcType == null) {
            return Optional.empty();
        }
        String slug = CONSTRUCTION.get(ifcType.trim().toUpperCase(Locale.ROOT));
        return slug == null || slug.isEmpty() ? Optional.empty() : Optional.of(slug);
    }
}
