package org.tornotron.echno_backend.modules.inspections.api;

/**
 * Who the event log names as having posted a finding, for a caller that is not the producer's own
 * token: a job inside the application posting a model's detections, say.
 *
 * @param kind AI for a model, DEVICE for anything else
 * @param id   the model or device, as the log should show it
 */
public record IntakeActor(Kind kind, String id) {

    public enum Kind { AI, DEVICE }

    public static IntakeActor ai(String id) {
        return new IntakeActor(Kind.AI, id);
    }
}
