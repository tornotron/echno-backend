package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.tornotron.echno_backend.modules.documentingestion.parsing.DxfReading;

/**
 * Reads the title block of each sheet of a DXF from the worker's {@code dxf.json}: the attributes of
 * the block reference that looks like a title block, by their tags ({@code DWG_NO}, {@code TITLE},
 * {@code REV}, ...), one sheet per paper space layout that holds one. No LLM.
 */
public final class DxfTitleBlocks {

    /** One attribute value read, with its tag. */
    public record Value(String text, String tag) {
    }

    /**
     * One sheet: the layout it is on, the values by field, and whether the number came from the layout's
     * name rather than an attribute.
     */
    public record Sheet(String layout, Map<String, Value> values, boolean numberFromLayout) {
    }

    private static final Map<String, Set<String>> TAGS = Map.of(
            DrawingFields.NUMBER, Set.of("DWGNO", "DRGNO", "DRAWINGNO", "DRAWINGNUMBER", "DWGNUMBER", "DRGNUMBER",
                    "DWGNUM", "DRAWINGNUM", "SHEETNUMBER", "NUMBER"),
            DrawingFields.REVISION, Set.of("REV", "REVISION", "REVNO", "CURRENTREV", "DWGREV", "DRGREV"),
            DrawingFields.DATE, Set.of("DATE", "REVDATE", "ISSUEDATE", "DWGDATE", "DRGDATE"),
            DrawingFields.SCALE, Set.of("SCALE", "DWGSCALE"),
            DrawingFields.DISCIPLINE, Set.of("DISCIPLINE"));
    private static final Set<String> TITLE_TAGS = Set.of("TITLE", "DWGTITLE", "DRAWINGTITLE", "DRGTITLE", "SHEETTITLE");

    private DxfTitleBlocks() {
    }

    public static List<Sheet> read(DxfReading dxf) {
        Map<String, List<DxfReading.Insert>> byLayout = new LinkedHashMap<>();
        for (DxfReading.Insert insert : dxf.inserts()) {
            if (insert.titleBlockLike() || hasNumberTag(insert)) {
                byLayout.computeIfAbsent(insert.layout() == null ? "" : insert.layout(), k -> new ArrayList<>())
                        .add(insert);
            }
        }
        Set<String> paper = new java.util.HashSet<>();
        for (DxfReading.Layout layout : dxf.layouts()) {
            if (!layout.modelSpace()) {
                paper.add(layout.name());
            }
        }
        boolean anyPaper = byLayout.keySet().stream().anyMatch(paper::contains);
        List<Sheet> sheets = new ArrayList<>();
        for (Map.Entry<String, List<DxfReading.Insert>> e : byLayout.entrySet()) {
            if (anyPaper && !paper.contains(e.getKey())) {
                // A title block in model space beside sheets that have their own is a drawing kept for reference.
                continue;
            }
            Map<String, Value> values = new LinkedHashMap<>();
            List<String> title = new ArrayList<>();
            String titleTag = null;
            for (DxfReading.Insert insert : e.getValue()) {
                for (DxfReading.Attribute a : insert.attributes()) {
                    String value = a.value() == null ? "" : a.value().strip();
                    if (value.isEmpty()) {
                        continue;
                    }
                    String tag = tag(a.tag());
                    if (TITLE_TAGS.contains(tag) || tag.matches("(DWG|DRAWING|DRG|SHEET)?TITLE(LINE)?\\d")) {
                        title.add(value);
                        titleTag = titleTag == null ? a.tag() : titleTag;
                        continue;
                    }
                    for (Map.Entry<String, Set<String>> t : TAGS.entrySet()) {
                        if (t.getValue().contains(tag)) {
                            values.putIfAbsent(t.getKey(), new Value(value, a.tag()));
                        }
                    }
                }
            }
            if (!title.isEmpty()) {
                values.put(DrawingFields.TITLE, new Value(String.join(" ", title), titleTag));
            }
            boolean fromLayout = false;
            if (!values.containsKey(DrawingFields.NUMBER) && DrawingFields.looksLikeNumber(e.getKey())
                    && !e.getKey().contains(" ")) {
                values.put(DrawingFields.NUMBER, new Value(e.getKey(), null));
                fromLayout = true;
            }
            if (values.isEmpty()) {
                continue;
            }
            Value number = values.get(DrawingFields.NUMBER);
            if (number != null && !values.containsKey(DrawingFields.DISCIPLINE)) {
                DrawingFields.disciplineFromNumber(number.text())
                        .ifPresent(d -> values.put(DrawingFields.DISCIPLINE, new Value(d, number.tag())));
            }
            sheets.add(new Sheet(e.getKey(), values, fromLayout));
        }
        return sheets;
    }

    private static boolean hasNumberTag(DxfReading.Insert insert) {
        return insert.attributes().stream().anyMatch(a -> TAGS.get(DrawingFields.NUMBER).contains(tag(a.tag())));
    }

    static String tag(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
