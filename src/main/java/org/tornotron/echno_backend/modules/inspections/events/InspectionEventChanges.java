package org.tornotron.echno_backend.modules.inspections.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Collects the fields a change touched as the two maps an event stores: the value before and
 * the value after, under the name the web contract uses for that field. A field whose value
 * did not change is left out, so a timeline entry says what moved and nothing else.
 */
public final class InspectionEventChanges {

    private final Map<String, Object> before = new LinkedHashMap<>();
    private final Map<String, Object> after = new LinkedHashMap<>();

    public static InspectionEventChanges none() {
        return new InspectionEventChanges();
    }

    /** Records {@code field} only when the two values differ. Enums are stored by their JSON value. */
    public InspectionEventChanges field(String field, Object oldValue, Object newValue) {
        Object oldJson = json(oldValue);
        Object newJson = json(newValue);
        if (!Objects.equals(oldJson, newJson)) {
            before.put(field, oldJson);
            after.put(field, newJson);
        }
        return this;
    }

    public boolean isEmpty() {
        return before.isEmpty() && after.isEmpty();
    }

    public Map<String, Object> before() {
        return before.isEmpty() ? null : before;
    }

    public Map<String, Object> after() {
        return after.isEmpty() ? null : after;
    }

    /** Value types the JSON column can hold, with enums by their wire name and dates as text. */
    public static Object json(Object value) {
        return switch (value) {
            case null -> null;
            case Enum<?> e -> wireName(e);
            case java.time.temporal.Temporal t -> t.toString();
            case java.util.UUID u -> u.toString();
            case Number n -> n;
            case Boolean b -> b;
            case String s -> s;
            default -> value.toString();
        };
    }

    private static String wireName(Enum<?> e) {
        try {
            return String.valueOf(e.getClass().getMethod("getValue").invoke(e));
        } catch (ReflectiveOperationException ignored) {
            return e.name();
        }
    }
}
