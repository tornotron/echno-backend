package org.tornotron.echno_backend.modules.vision.domain;

/**
 * Where an inference job is. The worker moves QUEUED to RUNNING and RUNNING to DONE or FAILED; the
 * backend poller returns a RUNNING job whose lease expired to QUEUED, or fails it when no attempts
 * are left. DONE and FAILED are terminal.
 */
public enum VisionJobStatus {
    QUEUED, RUNNING, DONE, FAILED
}
