package org.tornotron.echno_backend.modules.documentingestion.gate;

/**
 * Refuses a request to a module that is off for the caller's organization. Named so that clients do
 * not change when the platform gate replaces this module's own.
 */
public class ModuleDisabledException extends RuntimeException {

    /** Why the module is off, as it reads in the response. */
    public enum Reason {
        SWITCHED_OFF, NO_ORGANIZATION, NOT_ALLOWED, NOT_ENTITLED
    }

    public static final String CODE = "MODULE_DISABLED";

    private final String moduleId;
    private final Reason reason;

    public ModuleDisabledException(String moduleId, Reason reason) {
        super("The module " + moduleId + " is not available to this organization");
        this.moduleId = moduleId;
        this.reason = reason;
    }

    public String getModuleId() {
        return moduleId;
    }

    public Reason getReason() {
        return reason;
    }
}
