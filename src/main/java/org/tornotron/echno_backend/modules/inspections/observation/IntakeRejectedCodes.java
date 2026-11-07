package org.tornotron.echno_backend.modules.inspections.observation;

/** The stable codes the intake path answers a refused finding with. */
public final class IntakeRejectedCodes {

    public static final String VALIDATION = "VALIDATION";
    public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";
    public static final String ATTACHMENT_NOT_FOUND = "ATTACHMENT_NOT_FOUND";
    public static final String ATTACHMENT_NOT_ALLOWED = "ATTACHMENT_NOT_ALLOWED";
    public static final String INVALID_GEOMETRY = "INVALID_GEOMETRY";

    private IntakeRejectedCodes() {
    }
}
