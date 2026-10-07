package org.tornotron.echno_backend.modules.assistant.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a provider says about itself, so the planner can choose providers from metadata instead of
 * a hard-coded intent-to-provider map: adding a provider changes nothing in the planner.
 *
 * @param id                  unique, lower-case, for example {@code attendance}
 * @param answers             one paragraph on what the provider can answer, written for the planner
 * @param fields              every field its evidence carries, with units; never empty
 * @param subjects            the subjects it can answer; never empty
 * @param needsPeriod         whether a question needs a date range for this provider to answer it
 * @param carriesPersonalData whether its evidence can identify a person, so the planner selects it
 *                            only when the question needs it
 * @param cost                what a retrieval typically costs
 */
public record ProviderDescriptor(
        String id,
        String answers,
        List<FieldSpec> fields,
        Set<Subject> subjects,
        boolean needsPeriod,
        boolean carriesPersonalData,
        CostProfile cost) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    public ProviderDescriptor {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A provider id is lower-case letters, digits and hyphens: " + id);
        }
        if (answers == null || answers.isBlank()) {
            throw new IllegalArgumentException("Provider " + id + " must say what it can answer");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Provider " + id + " must declare the fields its evidence carries");
        }
        if (subjects == null || subjects.isEmpty()) {
            throw new IllegalArgumentException("Provider " + id + " must declare at least one subject");
        }
        if (cost == null) {
            throw new IllegalArgumentException("Provider " + id + " must state its cost profile");
        }
        Set<String> names = new HashSet<>();
        for (FieldSpec field : fields) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("Provider " + id + " declares the field " + field.name() + " twice");
            }
        }
        fields = List.copyOf(fields);
        subjects = Set.copyOf(subjects);
    }
}
