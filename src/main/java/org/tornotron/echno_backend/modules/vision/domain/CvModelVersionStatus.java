package org.tornotron.echno_backend.modules.vision.domain;

/** Where a model version is in its life. RETIRED is terminal, and at most one version of a model is ACTIVE. */
public enum CvModelVersionStatus {
    DRAFT, ACTIVE, RETIRED
}
