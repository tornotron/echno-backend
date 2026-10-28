package org.tornotron.echno_backend.modules.chat;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

/**
 * The manifest of the Chat module: one-to-one and group rooms, messages, reactions and the live
 * stream.
 *
 * <p>Optional and free: on for every organization, with no billing feature behind it, and switched
 * off for the whole installation by {@code echno.modules.chat.enabled=false}. To make it a paid
 * feature later, give the manifest an entitlement feature key, seed that feature onto the plans
 * that include chat, and gate the controller with {@code @RequireSubscription}.
 *
 * <p>Two permissions: {@value #PERMISSION_VIEW} to read rooms and messages, and
 * {@value #PERMISSION_CREATE} to take part (start rooms, send, edit and react). What a person may
 * do to one message, such as editing only their own, is decided in the service.
 *
 * <p>No navigation entry: the web app shows chat from its own header, not from the sidebar.
 */
@Component
public class ChatModule implements EchnoModule {

    public static final String ID = "chat";

    public static final String PERMISSION_VIEW = ID + ":view";
    public static final String PERMISSION_CREATE = ID + ":create";

    static final List<String> PERMISSIONS = List.of(PERMISSION_VIEW, PERMISSION_CREATE);

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Chat",
            "0.1.0",
            null,
            List.of(),
            PERMISSIONS,
            List.of(),
            true);

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }
}
