package org.tornotron.echno_backend.modules.inspections.events;

/**
 * Who, or what, caused an event. {@code USER} is a person acting through the API, identified
 * by employee id; {@code DEVICE} a camera or sensor intake; {@code AI} a model, identified by
 * its name; {@code SYSTEM} a scheduled job, identified by the job name.
 */
public enum InspectionEventActorType {
    USER,
    DEVICE,
    AI,
    SYSTEM
}
