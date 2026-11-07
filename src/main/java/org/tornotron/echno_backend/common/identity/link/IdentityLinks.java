package org.tornotron.echno_backend.common.identity.link;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.ExternalOrgId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;

/**
 * Writes the link between a provider's account or organization and the Echno row it is. Writing the
 * same link again changes nothing. A link joins the caller's transaction, so it stands or falls with
 * the row it describes.
 *
 * <p>The issuer is the running provider's own, from its {@link IdentityTokenMapper}, so it always
 * matches what a token from that provider maps to.
 */
@Component
public class IdentityLinks {

    private final UserIdentityRepository users;
    private final OrganizationIdentityRepository organizations;
    private final IdentityTokenMapper mapper;

    IdentityLinks(UserIdentityRepository users, OrganizationIdentityRepository organizations,
                  IdentityTokenMapper mapper) {
        this.users = users;
        this.organizations = organizations;
        this.mapper = mapper;
    }

    public void linkUser(ExternalUserId account, Long userId) {
        users.link(mapper.provider(), mapper.issuer(), account.value(), userId);
    }

    public void linkOrganization(ExternalOrgId providerOrganization, Long organizationId) {
        organizations.link(mapper.provider(), providerOrganization.value(), organizationId);
    }
}
