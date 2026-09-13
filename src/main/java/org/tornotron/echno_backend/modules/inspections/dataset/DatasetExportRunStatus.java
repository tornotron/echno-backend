package org.tornotron.echno_backend.modules.inspections.dataset;

import com.fasterxml.jackson.annotation.JsonValue;

/** Lifecycle of one export run. A run is synchronous, so it is never queued. */
public enum DatasetExportRunStatus {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    DatasetExportRunStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
