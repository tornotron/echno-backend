package org.tornotron.echno_backend.modules.vision.domain;

/** What a family's models produce. DETECT draws boxes, SEGMENT draws polygons. */
public enum CvModelTask {
    DETECT, SEGMENT
}
