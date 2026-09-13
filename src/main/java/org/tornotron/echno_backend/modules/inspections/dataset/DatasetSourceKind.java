package org.tornotron.echno_backend.modules.inspections.dataset;

/** Where an exported image came from inside the inspections module. */
public enum DatasetSourceKind {
    /** An {@code INSPECTION_EVIDENCE} attachment filed against the inspection itself. */
    INSPECTION_EVIDENCE("inspection_evidence", "inspection-evidence"),
    /** A photo reference held in {@code InspectionDefect.photos}. */
    DEFECT_PHOTO("defect_photo", "defect-photos"),
    /** An {@code OBSERVATION_EVIDENCE} attachment filed against an observation. */
    OBSERVATION_EVIDENCE("observation_evidence", "observation-evidence");

    private final String manifestValue;
    private final String folder;

    DatasetSourceKind(String manifestValue, String folder) {
        this.manifestValue = manifestValue;
        this.folder = folder;
    }

    /** The value written to the manifest's {@code source} field. */
    public String manifestValue() {
        return manifestValue;
    }

    /** The folder under the run prefix the copies land in. */
    public String folder() {
        return folder;
    }
}
