package org.tornotron.echno_backend.common.identity.keycloak;

/** The order the identity startup tasks run in. Identity first, since later ones join through it. */
final class StartupTaskOrder {

    static final int IDENTITY_BACKFILL = 100;
    static final int ROLE_IMPORT = 300;

    private StartupTaskOrder() {
    }
}
