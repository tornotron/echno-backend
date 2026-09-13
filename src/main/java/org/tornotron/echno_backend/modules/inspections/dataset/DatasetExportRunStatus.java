package org.tornotron.echno_backend.modules.inspections.dataset;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of one export run. A run is {@code running} from the moment the endpoint answers
 * until the background copy closes it; there is no queued state, because a second run is
 * refused while one is running.
 */
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
