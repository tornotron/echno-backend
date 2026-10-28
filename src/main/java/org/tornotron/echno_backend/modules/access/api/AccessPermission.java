package org.tornotron.echno_backend.modules.access.api;

import org.tornotron.echno_backend.common.module.ModuleManifest;

/**
 * One permission: an area and what may be done in it, written {@code <area>:<action>}, the same
 * vocabulary module manifests declare and {@code @PreAuthorize} authorities use. The five common
 * actions are {@code view}, {@code create}, {@code edit}, {@code approve} and {@code delete}; an
 * area may add named ones, such as {@code progress-bills:certify}.
 *
 * @param area   the area, for example {@code finance} or a module id
 * @param action what may be done, for example {@code view} or {@code certify}
 */
public record AccessPermission(String area, String action) {

    public static final String VIEW = "view";
    public static final String CREATE = "create";
    public static final String EDIT = "edit";
    public static final String APPROVE = "approve";
    public static final String DELETE = "delete";

    public AccessPermission {
        ModuleManifest.requirePermissionKey(area + ":" + action);
    }

    /** Parses {@code <area>:<action>}. */
    public static AccessPermission of(String key) {
        ModuleManifest.requirePermissionKey(key);
        int colon = key.indexOf(':');
        return new AccessPermission(key.substring(0, colon), key.substring(colon + 1));
    }

    /** The {@code <area>:<action>} form. */
    public String key() {
        return area + ":" + action;
    }

    @Override
    public String toString() {
        return key();
    }
}
