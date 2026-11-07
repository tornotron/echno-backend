package org.tornotron.echno_backend.modules.documentingestion.domain;

/** Whether the tender is still being bid or has been won; a package can exist with no project. */
public enum PackageStage {
    TENDER,
    AWARDED
}
