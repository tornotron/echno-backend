package org.tornotron.echno_backend.common.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.Nullable;

/**
 * Where Echno's own email goes and who it comes from.
 *
 * @param provider    {@code none} (the default), {@code log} or {@code ses}.
 * @param from        the sender, as an address or {@code Name <address>}. Required unless the provider
 *                    is {@code none}. Under SES its domain must be a verified identity.
 * @param replyTo     an optional reply address, for a sender that takes no mail itself.
 * @param productName the name the templates sign with.
 * @param ses         the SES client settings.
 */
@ConfigurationProperties("echno.mail")
public record MailSettings(@Nullable String provider, @Nullable String from, @Nullable String replyTo,
                           @Nullable String productName, @Nullable Ses ses) {

    /**
     * @param region           the SES region. Required under {@code ses}.
     * @param profile          a named profile from the shared AWS config, for a local run. Blank means the
     *                         default chain: environment variables, then the profile, then the instance
     *                         role, which is what a production host uses.
     * @param configurationSet an optional SES configuration set every message is sent under.
     */
    public record Ses(@Nullable String region, @Nullable String profile, @Nullable String configurationSet) {
    }

    public String providerName() {
        return provider == null || provider.isBlank() ? "none" : provider.trim();
    }

    public String product() {
        return productName == null || productName.isBlank() ? "Echno" : productName.trim();
    }

    public Ses sesOrEmpty() {
        return ses == null ? new Ses(null, null, null) : ses;
    }
}
