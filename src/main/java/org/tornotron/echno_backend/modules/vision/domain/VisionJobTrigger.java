package org.tornotron.echno_backend.modules.vision.domain;

/** Why a job was queued: an evidence image arrived for an organization with auto-inference on, or a person asked. */
public enum VisionJobTrigger {
    AUTO, ON_DEMAND
}
