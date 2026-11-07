# WorkOS build plan

Status: proposed, for review. Nothing here is built yet. Tracking: #914. Target repos:
`echno-backend`, `echno-core`, `echno-web`, and later `echno-android` and `echno-ios`.

The design lives in [Access Matrix and Identity Provider](2026-10-30-access-matrix-and-identity-provider.md)
and [Identity Provider Independence](2026-10-30-identity-provider-independence.md). This note says what
to build, in what order, and what each PR holds.

## 1. What we are building

- The first production deployment is a brand-new organization with no existing users. It goes live
  on WorkOS with no Keycloak in its stack.
- Apps sign in with the provider (option A). Echno's own tokens are deferred. The four cheap steps
  are in this plan: identity tables (steps 8, 9), organization choice in Echno (step 11), sign-in
  behind one module per app (steps 23, 34, 35), minimum app version check (step 21).
- Both stagings stay on Keycloak through its adapter until a later migration. This plan changes
  nothing on them. Neither one receives this work until `auth-workos` reaches `development`, and that
  promotion is a separate decision (section 10).
- The access matrix lands alongside: nine roles, levels, roles held per project, invitation links
  only, and the rules in section 4.

Module tiers and the Backend Architecture Redesign (#926 and its sub-issues) are parked and nothing
here depends on them.

### Setting per environment

| Environment | `echno.identity.provider` (`ECHNO_IDENTITY_PROVIDER`) | Web `AUTH_PROVIDER` |
| --- | --- | --- |
| Unit and integration tests | `fake` | not used |
| Local development | `keycloak` (the dev compose file keeps it) | `keycloak` |
| Both stagings | unset, which means `keycloak` | unset, which means `keycloak` |
| First production deployment | `workos` | `workos` |
| Sandbox proof runs (steps 0, 26) | `workos`, against the WorkOS staging environment | `workos` |

The default stays `keycloak` so a staging deploy needs no config change. A production stack that
forgets the setting fails at boot, because the Keycloak adapter refuses to start with its settings
blank. `fake` refuses to start outside the test and local profiles.

## 2. WorkOS facts this plan relies on

Read from the source on 2026-11-07 and updated from the step 0 runs on 2026-11-08 (section 2.1). Where a
fact drives a step, the step names it.

| # | Fact | Consequence | Source |
| --- | --- | --- | --- |
| F1 | Session access tokens carry no `aud` by default, confirmed on a real token. A JWT template can add one, and there is one template per environment. | Today's `AudienceValidator` would refuse every token. Set `{"aud": "<api id>"}` in the template and keep the check. `aud` is absent from the template's reserved keys (`iss`, `sub`, `exp`, `iat`, `nbf`, `jti`, `act`). A template `aud` has not been seen yet. | [Verify tokens](https://workos.com/blog/verify-workos-access-tokens-in-your-own-api), [JWT templates](https://workos.com/docs/authkit/jwt-templates) |
| F2 | A real token's `iss` is `https://api.workos.com/user_management/<client_id>`, no trailing slash, and it matches that address's discovery document. It stays the same after a refresh. The docs show other forms, and it changes with a custom auth domain. `authkit-nextjs` warns that it differs from the AuthKit OAuth issuer. | Pin `ECHNO_JWT_ACCEPTED_ISSUERS` from a decoded token, after the custom domain is set. The custom domain form is still unseen. Never copy the value from the AuthKit discovery document. | [Sessions](https://workos.com/docs/authkit/sessions), [Session tokens](https://workos.com/docs/reference/authkit/session-tokens), [Verify tokens](https://workos.com/blog/verify-workos-access-tokens-in-your-own-api) |
| F3 | Signing keys are at `https://api.workos.com/sso/jwks/{client_id}`, which answers with no credentials, one RS256 signing key and `max-age=300`. The discovery document names the same address. A real token verifies against it, including after a refresh. Plain `http` answers 301 to `https`, and an unknown client id answers 404. | The adapter derives this address from the client id. | [Session tokens](https://workos.com/docs/reference/authkit/session-tokens), [Verify tokens](https://workos.com/blog/verify-workos-access-tokens-in-your-own-api) |
| F4 | A real token with no organization carries `auth_time`, `client_id`, `email`, `exp`, `iat`, `iss`, `jti`, `sid`, `sub`. With an organization it adds `org_id`, `role`, `roles`, `permissions`. `entitlements` and `feature_flags` did not appear. The docs' claims table omits `email`, and no template is known to add it, so whether it is a stable default is unconfirmed. There is no email-verified claim and no sign-in method. `authentication_method` comes back in the authenticate response; the Session object has `auth_method`. | Echno keeps reading `sub` and `iss` only. The token's `email` is unverified as far as the token shows, so linking still reads email and `email_verified` from the Users API (step 18). `Principal.authentication` is empty, and an Echno rule cannot require MFA from the token. Tokens hold personal data, so they never reach a log. | [Session tokens](https://workos.com/docs/reference/authkit/session-tokens), [Authentication](https://workos.com/docs/reference/authkit/authentication), [Session](https://workos.com/docs/reference/authkit/session) |
| F5 | Refresh tokens rotate on use, with a documented 30 second replay grace. A used token replayed at once returned the same rotated token, and a replay about 35 seconds after first use still answered 200, so the grace is longer than documented or differs here. The rotated token kept working. `invalid_grant` ends the session; `408`, `429` and `5xx` are transient. | The kit and any mobile code retry transient failures and sign out only on `invalid_grant`. | [Session resilience](https://workos.com/docs/authkit/session-resilience) |
| F6 | Sessions can be listed per user and revoked one by one. After a revoke, the issued access token still verifies against JWKS, since that check is local, and it lasts until `exp` (300 seconds by default). The refresh token answers `invalid_grant`, "Session has already ended", with or without the secret. The person's other sessions keep working, a new sign-in works, and the list no longer shows the revoked session. | Removal takes effect through Echno's per-request membership read, and access tokens stay short. | [Session](https://workos.com/docs/reference/authkit/session) |
| F7 | Users are unique by email within one environment. Projects are a free grouping and each holds its own staging and production environments, with users, organizations and keys per environment. | One WorkOS project per client stack, so clients share no accounts and no settings. | [Users and organizations](https://workos.com/docs/authkit/users-organizations), [Projects](https://workos.com/docs/authkit/projects) |
| F8 | Invitations have `state` (pending, accepted, expired, revoked), `accepted_user_id`, `accepted_at`, `revoked_at`, `expires_at`, `role_slug`, `inviter_user_id`, `token`, `accept_invitation_url`, and send, resend (pending only), revoke, accept, get, get by token and list calls (filters: organization, email). `expires_in_days` is an input only, 1 to 30, default 7. Resend keeps the same id, token and `expires_at`. Revoke then send again gives a new id and token. A second pending invitation to the same address and organization is refused. | Maps onto the port and the invitation screen with little of our own. Resend cannot renew an invitation, so a renewal is revoke then send (step 17). `token` and `accept_invitation_url` are bearer values and never reach a log (step 16). | [Invitation API](https://workos.com/docs/reference/authkit/invitation) |
| F9 | WorkOS sends invitation, password reset and verification email itself by default, and we can turn any type off and send our own. Production mail comes from `workos-mail.com` unless a custom domain is set, and that shared domain is "best-effort and cannot be guaranteed". A custom domain needs three CNAME records. | The backend needs no mail sender for sign-in. The stack needs the custom email domain before real traffic (section 8). | [Invitations](https://workos.com/docs/authkit/invitations), [Custom emails](https://workos.com/docs/authkit/custom-emails), [Email domain](https://workos.com/docs/custom-domains/email) |
| F10 | The docs say that on a corporate email domain an organization invitation can be accepted with any address on that domain, and on a consumer domain the address must match exactly. Through the password grant, a different `example.com` address was refused with `invitation_cannot_be_used_for_email`, and the invitation stayed pending. How WorkOS classes `example.com`, and what hosted AuthKit does, are both unknown. | Our rule is the exact invited address, so Echno keeps its check at linking (section 3) until a hosted test says otherwise. | [Invitations](https://workos.com/docs/authkit/invitations) |
| F11 | Sign-up can be turned off per environment. A valid invitation code in the flow opens registration again for that person. | This is the invite-only model the owner asked for, with no code of ours. | [Invite-only signup](https://workos.com/docs/authkit/invite-only-signup), [Invitations](https://workos.com/docs/authkit/invitations) |
| F12 | Automatic membership by email domain and SSO provisioning are on by default for organizations with verified domains, and both can be turned off. | Verify no domains, turn both off, and Echno ignores provider membership in any case. | [JIT provisioning](https://workos.com/docs/authkit/jit-provisioning) |
| F13 | `authkit-nextjs` 4.6.3 accepts Next 16 and React 19 and needs `@workos-inc/node` 9 to 12. `withAuth()` returns `accessToken`, `authkit` composes with an existing proxy, and refresh and cookie handling are built in. WorkOS's next-auth page covers SSO only. The library reads only `NEXT_PUBLIC_WORKOS_REDIRECT_URI`, never `WORKOS_REDIRECT_URI`. It checks the token's issuer only when `WORKOS_ISSUER` is set. It enforces PKCE on every callback, so a flow WorkOS starts needs the Initiate login URI. | Web uses `authkit-nextjs` for WorkOS and keeps next-auth for Keycloak, both behind one module (steps 23, 24). One redirect variable, plus `WORKOS_ISSUER` (section 7). | [Next.js](https://workos.com/docs/authkit/nextjs), [authkit-nextjs](https://github.com/workos/authkit-nextjs), [next-auth page](https://workos.com/docs/integrations/next-auth), npm registry |
| F14 | Webhooks are signed (`WorkOS-Signature`, HMAC SHA256), retried up to 6 times over 3 days in production, unordered, and may repeat. | No webhook at go-live. A sync job covers the need (step 18). A later endpoint would reuse the billing webhook inbox. | [Webhooks](https://workos.com/docs/events/data-syncing/webhooks) |
| F15 | Hosted AuthKit MFA is TOTP only. Set to Required, it applies to all users, and it does not apply to SSO users. An organization policy can also require it. | No per-role MFA (section 3). | [MFA](https://workos.com/docs/authkit/mfa), [Organization policies](https://workos.com/docs/authkit/organization-policies) |
| F16 | The custom auth domain needs one CNAME and applies in production; staging uses `*.authkit.app`. The docs show no per-organization sign-in page or address. | One sign-in address per client stack. This also answers the owner's "enterprise domain" question (design note section 7). | [Custom domain](https://workos.com/docs/custom-domains/authkit), [Organization policies](https://workos.com/docs/authkit/organization-policies) |
| F17 | AuthKit's first 1M monthly active users are free, then $2,500 per 1M. Custom domain $99/month. SSO and directory sync from $125 per connection per month. | Cost at launch is the custom domain. The pricing page does not say whether that price covers both the auth and the email domain; step 0 asks. | [Pricing](https://workos.com/pricing) |
| F18 | Password import accepts `bcrypt`, `scrypt`, `firebase-scrypt`, `ssha`, `ssha256`, `pbkdf2`, `argon2`. `pbkdf2` takes PHC form with digest `sha1`, `sha256` or `sha512` and iteration limits. Keycloak is not named. | Only matters for the later staging migration: Keycloak's format looks importable, and needs a proof with one real hash. | [Other services](https://workos.com/docs/migrate/other-services), [User API](https://workos.com/docs/reference/authkit/user) |
| F19 | User Management allows 1,000 reads and 500 writes per 10 seconds. Each sandbox has a 24-hour invitation email quota (100 attempts), which also applies with a custom email provider. Production limits are not published. | The adapter honours `Retry-After`. Sandbox tests stay small. Ask WorkOS about production limits before a large invite run. | [Rate limits](https://workos.com/docs/reference/rate-limits), [Invitations](https://workos.com/docs/authkit/invitations) |
| F20 | Native sign-in works by PKCE with a public client id. iOS has an SDK; Android's is a pre-release at 0.2.1. Separate Applications per platform share users, and `client_id` tells them apart. The API reference now marks `client_secret` optional on refresh and on the PKCE code exchange, and both SDK guides refresh without one. A real refresh with no secret answered 200 and rotated the token, and the password grant with no secret answered `invalid_client`. This was on the environment's default Application. A PKCE code exchange with no secret passed client authentication on a bad code, and a real one needs a browser. | Mobile keeps its own PKCE code and points it at WorkOS. Step 36 rechecks refresh on the Application it uses. | [iOS SDK](https://workos.com/docs/sdks/ios), [Android SDK](https://workos.com/blog/sign-in-to-an-android-app-with-the-workos-android-sdk), [Applications](https://workos.com/docs/authkit/applications), [Authentication](https://workos.com/docs/reference/authkit/authentication) |
| F21 | A password grant for a user in two organizations answers 403 `organization_selection_required`, with `organizations` (`id`, `name`), `pending_authentication_token` and `user`. The selection grant then gives a token with that `org_id`. Refresh keeps it, and refresh with `organization_id` moves it to the other organization with no secret. With one organization, `org_id` is set without a choice. With none, it is absent. Hosted AuthKit handles the error itself, and the docs do not say what the person sees. | Echno ignores `org_id`, so any picker is cosmetic. A token can have no `org_id`, so the mapper must not need one (step 15). | [Organization selection error](https://workos.com/docs/reference/authkit/authentication-errors/organization-selection-error) |
| F22 | A Java SDK `com.workos:workos` exists. The client libraries page does not say what it covers. | The adapter uses Spring's `RestClient` against the nine documented endpoints it needs, with no new runtime dependency. | [Client libraries](https://workos.com/docs/reference/client-libraries) |
| F23 | Sending an invitation creates the WorkOS user at once, with `email_verified` false, plus a `pending` organization membership. For a person who already has an account, it adds the `pending` membership only. Creating a user at an invited address then fails with `email_not_available`. Revoke leaves the user and the pending membership in place, and deleting that membership works. The accept call activates the membership and sets `accepted_user_id`, and it leaves `email_verified` false. Deleting the user leaves the invitation pending. | Provider user and membership counts include people who never signed in. `accepted` says nothing about a verified email (step 18). A revoke also removes the pending membership (step 16). | Step 0 run |
| F24 | Each staging environment comes with a Test Organization, an active SSO connection and the verified domain `example.com`. In the run, a password grant for `example.com` addresses answered `sso_required` naming one connection, with or without an invitation token, and each user created there with a verified email became an active member of the seeded organization, which is F12's default at work (the event log confirms it). Invited users did not join that way. `example.org` addresses joined nothing and the password grant worked. Magic Auth can be off per environment (`authentication_method_not_allowed`). | No sandbox test can get a token with an `example.com` address, and creating verified users there adds members to the seeded organization (steps 16, 26). The same would reach real users if a domain were verified, which is one more reason for F12's setting. | [Test SSO](https://workos.com/docs/sso/test-sso), step 0 run |

### 2.1 Step 0 results (2026-11-08)

Run against a WorkOS staging environment, API only, in two passes. All objects they created were
deleted afterwards. The first pass used `example.com` addresses and stopped at F24 for anything needing
a token. The second used `example.org` addresses and obtained real tokens through the password grant.

| Item | Status | Result |
| --- | --- | --- |
| Exact `iss` and all claims on a real token | PROVEN on the default domain | F2, F4. The token includes `email`, which the docs' table omits. Custom domain form unseen. |
| JWKS address | PROVEN | F3. |
| Token verifies against JWKS | PROVEN | RS256, before and after refresh (F3). |
| Refresh with no client secret, and with it | PROVEN on the default Application | Both answer 200 and rotate the token (F20). Replay in F5. |
| Access token after session revoke; refresh token then | PROVEN | Access token still verifies, refresh token answers `invalid_grant` (F6). Rejection at `exp` was not waited for. The plan already assumed this. |
| Person in two organizations; `org_id` | PROVEN for the API, NEEDS A HUMAN for hosted | F21. Step 11 ignores `org_id` either way. |
| Invitation fields and state moves | PROVEN | F8, F23. `pending` to `accepted` and `pending` to `revoked` seen. `expired` not waited for. Refusals: `invite_accepted`, `invite_revoked`, `invite_not_pending`, `email_already_invited_to_organization`, `expires_in_days_too_short`, `expires_in_days_too_long`. |
| Different same-domain address accepts | PROVEN for the password grant, NEEDS A HUMAN for hosted | Refused on `example.com` (F10). |
| `aud` without a template; with one | PROVEN absent, NEEDS A HUMAN with a template | F1. The template needs the dashboard. |
| `authkit-nextjs` with a runtime-only redirect variable in a shared image | PROVEN from source, 4.6.3 and Next 16.3.3 | Works if the variable is unset during `next build`. Next inlines `process.env.NEXT_PUBLIC_*` only for values present at build, so a value set at build is frozen into the image, and an unset one stays a server read at run time. Client code never sees it, and the library's client components do not read it. Order of use: `redirectUri` option, then the `x-redirect-uri` header the proxy sets, then the variable. The other `WORKOS_*` values are read at run time. Not yet run in a real image (step 24 tests it). |
| Google or Microsoft sign-up with sign-up off | NEEDS A HUMAN | Question 1 below. |
| Second Application's `client_id` for JWKS | NEEDS A HUMAN | Question 2 below. |
| What the custom domain price covers | NEEDS A HUMAN | Question 3 below. |

**Dashboard steps for the JWT template.** The docs name the page but no buttons, so the last step is
described by its purpose.

1. Sign in at `dashboard.workos.com`, choose the project, and switch to the Staging environment.
2. Open **Authentication**, then **Features** (`dashboard.workos.com/environment/authentication/features`).
3. Open **JWT Template**, set the template to `{"aud": "<api id>"}` with the same value as
   `ECHNO_JWT_AUDIENCE`, and save.
4. Rerun the script with an `example.org` address. It reads `aud` from a new sign-in.

**Hosted checks, by a person.** These need a browser. Sign-up is the **Sign up** toggle in the
dashboard's authentication section, per environment.

- With sign-up off, choose Google, then Microsoft, on the hosted page with an account the environment
  does not hold. Record whether an account appears, then repeat from an invitation link.
- Invite `a@<company domain>`, open the link, and sign up as `b@<company domain>`. Record whether it
  is allowed and what `accepted_user_id` holds.
- Give one person two organizations, sign in through hosted AuthKit, and record what appears.

**Questions for WorkOS**

1. With sign-up turned off for an environment, can a person with no account and no invitation create
   one by choosing Google or Microsoft on hosted AuthKit? With an invitation code in the flow, does that
   sign-up work, and must the Google or Microsoft address match the invited address?
2. When one environment has two Applications, which `client_id` goes in `/sso/jwks/{client_id}` for a
   token issued to the second one? Do the two Applications share signing keys, and does `iss` differ
   between them?
3. Does the $99 per month custom domain price cover both the auth domain and the email domain, or is
   each billed separately? Is it charged per environment or per project?
4. A password grant token carries an `email` claim that your claims table omits, with no JWT template we
   know of. Is it a stable default, and is it also present after hosted AuthKit sign-in?
5. Your docs say a corporate domain invitation can be accepted with any address on that domain. The
   password grant refused a different `example.com` address with `invitation_cannot_be_used_for_email`.
   How is a domain classed as corporate, does hosted AuthKit apply the same check, and can exact matching
   be required?

### 2.2 What step 0 changed

| Step | Change |
| --- | --- |
| 15 | The mapper accepts a token with no `org_id` and reads neither `email` nor `org_id`. The issuer value for the default domain is known. |
| 16 | Revoke also deletes the pending membership. Refusal codes are mapped. Invitation bearer values are kept out of logs. The manual contract avoids `example.com`. |
| 17 | Renewal is revoke then send, and the record takes the new provider id. |
| 18 | Verified email comes from the Users API, because `accepted` does not imply it. |
| 24 | The redirect comes from one run-time variable, `baseURL` is passed, `WORKOS_ISSUER` is set, and an Initiate login route is added. |
| 26 | Test addresses avoid `example.com`. Hosted invitation acceptance is proved through the web callback. |
| 36 | Refresh with no secret is proved on the default Application and is rechecked on the mobile one. |
| Section 7 | One redirect variable, `WORKOS_ISSUER`, and the Initiate login URI setting. |

## 3. What WorkOS cannot do that the rules need

| Need | Gap | What we do |
| --- | --- | --- |
| The link opens only for the invited email (design note 3.4) | F10: the docs allow a same-domain address. The password grant refused one, and hosted AuthKit is untested | Echno compares the accepted user's verified email with the invited one, exactly. A mismatch links nothing and shows the administrator the invitation as accepted by another address (step 18). |
| Removal takes effect at once | F6: a revoke leaves an issued access token valid until `exp` | Echno checks membership on every request (step 11). Revoking sessions only stops new tokens. |
| MFA for System Admins only (today's Keycloak rule) | F15: no per-role setting | Not possible through AuthKit. Either the whole organization or nobody. Question in section 10. |
| A rule in Echno that requires MFA | F4: token has no sign-in method | No such rule in the first deployment. MFA is set in WorkOS. A missing value still counts as the weakest (design note 3.1), so a rule added later fails closed. |
| Machine logins (`observation-producer`) | Today they are Keycloak service accounts placed in groups. Under WorkOS the JWT template does not render into `client_credentials` tokens ([JWT templates](https://workos.com/docs/authkit/jwt-templates)). | Out of scope for the first deployment, which has no such device. Keycloak mode keeps them working (step 11). A machine credential is a later piece of work. |
| Roles, grants, scope, project, audit | WorkOS has roles and permissions of its own, in the token | Unused by design. Every membership stays on the default role and those claims are ignored. |

## 4. Rules: where each is enforced and tested

| Rule | Step | Test |
| --- | --- | --- |
| System roles' grants and grantable lists are immutable | 28 | `AccessAdminWriterIT`: rename, grant edit, grantable edit and delete each refused for System Admin and baseline |
| Last org admin cannot be removed | 13 | `LastAdminIT`: removal, self-removal, role delete, status change, and two concurrent removals on CockroachDB |
| Nobody changes their own roles or grants | 13, 28 | `SelfChangeRefusedIT`, on every write path including the old employee role endpoints |
| Only the org admin edits the baseline; `access:*` never on it | 28, template check in 12 | `BaselineRoleIT`; a template test fails the build if a seed puts `access:*` on the baseline |
| Non-All scope refused for areas not filter-aware | 28 | `ScopeRefusalIT`, driven from the area registry |
| Append-only audit history | table in 2, writers in 13, 17, 18, 28 | Every writer asserts a row. An ArchUnit rule allows `access_change_log` only an insert. |
| Screen and old checks never disagree | 11, 13 | `LegacyMirrorIT` per role; a role with a legacy counterpart refuses a project (section 6, step 13) |
| Backfill so nobody loses access | 10, 12 | `PreMigrationOrgIT`: an organization and employees built the old way, then the runners, then the same access |
| Cache eviction after commit | 3, 11 | A rolled-back change evicts nothing; a committed one is seen on the next read |
| No per-check N+1 | 3, 11 | Query counter: a list of 100 records runs the facts query once |
| Every role link scoped to the row's org | 2, 3 | Composite foreign keys; `OrgIntegrityIT` tries each cross-org link at the database and at the service |

## 5. Keycloak mentions, by concern

55 backend Java files, 35 web source files and 4 core files mention Keycloak (web's count at the time
of the earlier note was 28). Most are comments. Grouped by what has to happen:

| Concern | Files | What happens |
| --- | --- | --- |
| Token to authorities | `JwtAuthConverter`, `JwtDecoderConfig`, `OrgRoleAuthority`, `OrganizationSecurityService`, `TenantFilter` (no mention, same seam) | The converter delegates to the mapper and the new authority source. Authority names do not change, so `@orgSecurity`, `TenantFilter` and every guard stay as they are (step 11). |
| Provider calls | `KeycloakUserProvisioningService`, `KeycloakGroupService`, callers `UserService`, `OrganizationService`, `EmployeeService`, `DevFixtureProvisioner` | Into the Keycloak adapter; callers use the port (step 6). |
| Keycloak-only machinery | `KeycloakConfig`, `KeycloakConfigGenerator`, `KeycloakInitializer` and its properties, `KeycloakAuthorizationService`, `RPTExchangeFilter`, `RPTCache`, `RPTExchangeException`, `keycloak/*` management controller, service and DTOs, `KeycloakGroupController` | Conditional on the setting, so absent under WorkOS (step 7). |
| Subject as user id | `User`, `UserRepository`, `UserContextService`, `UserService`, `UserController`, `UserControllerWeb`, `UserKeycloakDto`, `EmployeeDto`, `EmployeeMapper`, `EmployeeSalaryVisibility`, `CurrentEmployeeService`, `OrganizationService` audit text | Through `user_identity` (steps 8, 9, 33). |
| Sign-up and join | `AuthController`, `EmployeeController`, `EmployeeControllerWeb`, `ProjectInviteCode*` | Removed or gated (step 19). |
| Comments and API docs only | `PlanController`, `OpenApiConfig`, `LeaveCalendarController`, `NotificationController`, `LowStockSweep`, `InspectionSecurityService`, `ObservationIntakeController`, `EmployeeRepository`, `EmployeeHierarchyService`, `Employee`, `UserNameDirectory`, `OrganizationOnboardingSeeder`, `OrgRole` | Reworded when the code beside them changes. `OpenApiConfig` needs an oauth flow per provider (step 15). |
| Config | `application.yml` lines for `jwt.auth.converter`, `keycloak-initializer`, `keycloak`, `resourceserver.jwt.jwk-set-uri`; `keycloak-templates/` | Placeholders get empty defaults and a validator (step 7). `principle-attribute: preferred_username` becomes per mapper, since WorkOS has no such claim. |
| Web sign-in | `auth.ts`, `config/auth-config.ts`, `lib/auth/*`, `hooks/use-session-lifecycle.ts`, `types/next-auth.d.ts`, `types/keycloak/*`, `app/api/auth/*`, `services/auth-service.ts`, `proxy.ts` | Behind `lib/auth` (step 23), with a WorkOS implementation beside it (step 24). |
| Web callers of the session | about 25 files using `useSession`, `signIn('keycloak')` or `auth()` | Moved to the module (step 23). |
| Web wording | `app/features`, `app/privacy`, `components/common/auth-button`, `features/home/welcome-screen`, `README`, docs | Reworded at step 25. |
| Core | `role-management-service.ts` and its hook (calls `/keycloakGroup/web/*`), `user-role.ts` and `user.ts` comments | The hook moves to the access endpoints, then goes (steps 22, 32). |
| Mobile | Android `org.echno.core.auth` (`KeycloakTokenService`, `AuthEndpoints`, `AccessTokenClaims`, `SessionManager`), iOS `Sources/EchnoKit/Auth` (`KeycloakAuth`, `KeycloakAuthenticator`, `PKCE`) | Steps 34 to 36. |

The audit's fourth join path, `POST /employee/web/bulk/confirm`, is on neither `development` nor
`auth-workos` as of 2026-11-07. If it lands, it goes through invitations.

## 6. Steps

Each step is one PR into `auth-workos` in that repo, merged bottom-up by me with every check green
and one human approval. Backend, then core, then web, then mobile. Every backend step leaves
Keycloak environments behaving as they do today unless it says otherwise.

**Gate A** (step 26) is the point the first production deployment can run. **Gate B** (step 29) is
the point the Roles & Permissions screen is usable.

### Before any code

**Step 0. WorkOS sandbox proof.**

- Builds: One docs PR updating this note's section 2. Against a WorkOS staging environment, with a
  throwaway script kept out of the repo, record: the exact `iss`; that a template `aud` appears;
  which `client_id` the JWKS address takes when a second Application exists; an invitation accepted
  with a different same-domain address; whether Google or Microsoft sign-in can create an account
  with sign-up off; what a person in two organizations sees; refresh with no client secret; whether
  an access token still validates after a session revoke; whether `authkit-nextjs` works with a
  runtime-only redirect variable in a shared image; what the custom domain price covers. Any
  surprise changes the steps below before they start.
- State: API runs done on 2026-11-08 (section 2.1). Open: the template `aud`, the hosted checks and the
  questions for WorkOS. The template `aud` must be seen before step 15's audience check runs against
  a real environment.

### Backend: matrix foundation

**Step 1. Access module and decision API.**

- Builds: Reuses `modules/access/api/*`, `AccessControlModule`, `ModuleManifest.core`,
  `ModuleRegistry` and `AccessServiceContract` from `access-control-module`. Adds the other-org case
  to the contract and the organization check in `AccessFilter.admits`. Leaves out that branch's
  `build.gradle` and `docker-compose.dev.yml` changes.
- Tests: `ModuleRegistryTest`, in-memory contract.
- Dark: nothing calls it.

**Step 2. Access tables.**

- Builds: Changesets and entities for `access_role`, `access_role_grant`, `access_employee_role`,
  `access_employee_grant`, `access_role_grantable`, `access_party_link`, `access_role_template`,
  `access_role_template_grant`, `access_change_log`. No groups. `project_id` (nullable) on both
  employee tables, with uniqueness that treats a null project as one value. Composite keys so a
  link's role and employee share its organization. Unique role name per organization ignoring case.
  Indexes on party links.
- Tests: repository ITs on CockroachDB, `OrgIntegrityIT`.
- Dark: nothing writes.

**Step 3. Engine.**

- Builds: `MatrixAccessService`, `DatabaseAccessFactsSource`, `CachedAccessFactsSource`. Facts
  include the project on each assignment. A project assignment narrows the grant's scope to that
  project. Memoized per request, then Caffeine and Redis for five minutes. Eviction runs in
  `TransactionSynchronization.afterCommit`, and on project membership changes. Statuses that hold
  access are `active`, `onLeave` and `probation`; everything else, and null, holds nothing.
- Tests: contract against the database engine, query counter, eviction on commit and on rollback.
- Dark: nothing calls it.

**Step 4. Guard.**

- Builds: `AccessGuard`, `CurrentAccessSubject`, the boot-time scan of `@PreAuthorize` expressions,
  the enforced flag on the catalog. A malformed key answers false. A startup test parses every
  `@access.can` literal.
- Tests: `AccessGuardTest`, literal test.
- Dark: no controller uses `@access` yet.

### Backend: identity

**Step 5. The port.**

- Builds: `common/identity`: `IdentityProvider`, `IdentityTokenMapper`, `Principal`, the id types,
  `IdentityProviderContract`, `FakeIdentityProvider`, and an ArchUnit rule that only adapter
  packages import provider types. Port surface: `createOrganization`, `deleteOrganization`,
  `addMember`, `removeMember`, `invite`, `resendInvitation`, `revokeInvitation`, `invitationState`,
  `profile`, `sendPasswordReset`, `endSessions`, plus `capabilities()`. Self-registration with a
  password is an optional extra only the Keycloak adapter has.
- Tests: contract against the fake.
- Dark: nothing calls it.

**Step 6. Keycloak adapter.**

- Builds: Moves `KeycloakUserProvisioningService` and `KeycloakGroupService` under
  `common/identity/keycloak` behind the port. `UserService`, `OrganizationService`,
  `EmployeeService` and `DevFixtureProvisioner` call the port. Role subgroup writes stay for now, on
  an adapter-only interface.
- Tests: contract against Keycloak Testcontainers; the existing suite passes unchanged.

**Step 7. Boots with no Keycloak.**

- Builds: `@ConditionalOnProperty` on everything in the "Keycloak-only machinery" row of section 5.
  `SecurityConfig` takes the RPT filter as optional. `allowedOrigins` reads
  `ECHNO_CORS_ALLOWED_ORIGINS`, falling back to the old variable. Keycloak placeholders in
  `application.yml` default to empty, and a validator fails boot when the chosen adapter's settings
  are blank. Environment variable names stagings use do not change.
- Tests: `NoKeycloakBootIT` starts the full context with `fake` and no `KEYCLOAK_*` variable set.
  The existing boot ITs stay.
- Dark: default is still `keycloak`.

**Step 8. Identity tables, write side.**

- Builds: `user_identity(provider, issuer, subject, user_id)` unique on issuer and subject;
  `organization_identity(provider, provider_org_id, organization_id)`. A runner backfills every user
  and organization under the Keycloak issuer, in its own transaction per batch, under a lock row.
  New users and organizations write both old and new columns. The Keycloak mapper normalizes every
  accepted issuer to one canonical value, since the internal and public addresses differ.
- Tests: `PreMigrationOrgIT` for identity, rerun changes nothing.
- Dark: nothing reads them.

**Step 9. Identity tables, read side.**

- Builds: `UserContextService`, `UserRepository`, `UserService`, `CurrentEmployeeService` and the
  API docs resolve through `user_identity`. `getCurrentKeycloakId` becomes `currentSubject`.
  `EmployeeSalaryVisibility` compares Echno user ids, and `EmployeeDto` carries `userId` in place of
  `userKeycloakId` (never published, so no API change). `users_table.keycloak_id` becomes nullable;
  it is dropped at step 33.
- Tests: salary visibility and user lookup suites; `openApiSnapshot` unchanged.

**Step 10. Clean data before the source moves.**

- Builds: A runner under the lock row: sets null `employee.status` to `active`; in Keycloak mode,
  imports each employee's Keycloak subgroup roles into `employee_org_roles` so the table is complete
  before it becomes the truth, logging every row it adds.
- Tests: `PreMigrationOrgIT` with a null status and a role present only in Keycloak.

**Step 11. Membership and roles from Echno.**

- Builds: The seam. `JwtAuthConverter` gets `Principal` from the mapper and asks
  `MembershipAuthorities`, which reads `employee` and `employee_org_roles` by `user_identity` and
  mints the same `ORG_MEMBER_{id}`, `ORG_{id}_ROLE_{role}` authorities. It reads with a projection
  query, because it runs before `TenantFilter` sets a tenant. `TenantFilter`, `@orgSecurity`,
  `OrgRoleAuthority` and all 611 guards keep working as written. Adds `platform_admin` and mints
  `ROLE_platform-admin` from it. Cached per subject and evicted after commit on role, status and
  membership changes. In Keycloak mode a service-account token keeps its groups from the token, so
  `observation-producer` keeps working. Closes audit G-18 (an inactive employee now loses access)
  and the token lag on role changes.
- Tests: `JwtAuthConverterGroupsTest` rewritten; removal seen on next request; an `org_id` in a
  token has no effect; one query per request.

**Step 12. Role templates and backfill.**

- Builds: Template rows for the nine roles, the four carried-over roles and the baseline (design
  note 2.5). `OrganizationProvisioning` copies them. A runner reconciles every organization on every
  boot (design note section 4).
- Tests: `PreMigrationOrgIT`, template test for `access:*`, rerun changes nothing.

**Step 13. Assignments and the mirror.**

- Builds: `AccessRoleAssignments` is the only writer. `LegacyRoleMirror` writes `employee_org_roles`
  in the same transaction. `EmployeeService.assignOrgRole` and `removeOrgRole` and the
  `/keycloakGroup/web/*role` endpoints go through it. The Keycloak subgroup write stays on the
  adapter-only interface until step 14. Rules 1, 4, 5, 6 and 7 of design note 2.6. A role with a
  legacy counterpart refuses a project (design note 2.7). Closes audit G-01.
- Tests: `LastAdminIT`, `SelfChangeRefusedIT`, `LegacyMirrorIT`, `HrAdminCannotGrantSystemAdminIT`.

**Step 14. Identity task queue.**

- Builds: `identity_task`: a repeat-safe queue written in the caller's transaction, retried by a
  scheduled job with backoff, for provider calls that must not hold a transaction or roll one back.
  Offboarding moves onto it: Echno's change commits and takes effect at once, then the provider's
  `removeMember` and, when no active employee remains, `endSessions` follow. Closes audit G-05 (a
  provider failure no longer leaves the person half removed). Keycloak subgroup writes move onto the
  queue too: no guard reads them after step 11, and `ensureAdminMfa` still does.
- Tests: failure and retry, duplicate delivery, status change ends access before the task runs.

**Step 15. WorkOS tokens.**

- Builds: `WorkOsTokenMapper`, which needs only `sub` and `iss`, since `org_id` can be absent (F21) and
  the token's `email` is not trusted (F4). `JwtDecoderConfig` builds per provider: JWKS from the client id (F3),
  required `aud` from `ECHNO_JWT_AUDIENCE` (F1), required issuer from the existing
  `ECHNO_JWT_ACCEPTED_ISSUERS` (F2). Under WorkOS an empty issuer list fails boot. `OpenApiConfig`
  per provider.
- Tests: tokens signed with a local JWK set: wrong issuer, missing audience, expired, another
  client's key, all refused; extra `org_id`, `role`, `permissions` ignored.
- Dark: `keycloak` is still the default.

**Step 16. WorkOS adapter.**

- Builds: `WorkOsIdentityProvider` on `RestClient`: organizations, memberships, invitations, users,
  sessions, password reset. Sets `external_id` on organizations and users. Honours `Retry-After`
  (F19). A health indicator confirms the API key, which replaces the deployment's Keycloak client
  check. The API key, the invitation `token` and `accept_invitation_url` never reach a log.
  `revokeInvitation` also deletes the pending membership the invitation made, and leaves the provider
  user alone (F23). Maps the refusal codes in section 2.1, and treats `email_not_available` as a user
  who already exists.
- Tests: contract against recorded fixtures; the same contract, tagged and manual, against the
  sandbox, using addresses outside `example.com` for anything that needs a token (F24).

**Step 17. Invitation records.**

- Builds: `invitation` and `invitation_role(invitation_id, role_id, project_id)` with the employee
  details the join form collects. `POST`, list, resend and revoke under `/api/v1/invitations/web`,
  guarded on the organization roles that may onboard today until the `org-admin` area converts.
  Validates roles with the same checks as an assignment. Sending goes through `identity_task`.
  Resend repeats the email and keeps the expiry (F8), so renewing an invitation is revoke then send,
  and the record takes the new provider id. The screen shows the expiry beside the resend action.
  Keycloak adapter: creates the account and sends Keycloak's built-in email. Regenerates
  `docs/openapi.json`.
- Tests: guards, cross-org, assignability, a sender naming a role they cannot give, self-invite,
  change log.
- Dark: no screen calls it.

**Step 18. Acceptance.**

- Builds: `IdentityLinkService.reconcile(principal)`, called from `GET /user/web`, the first call
  web makes after sign-in. It looks up pending invitations for the person's email, asks the adapter
  for each one's state, and links only when the state is accepted, the accepted subject is this
  subject, and the verified email matches exactly. The email and `email_verified` come from the Users
  API, because `accepted` does not imply a verified email (F23). In one transaction: the user row and identity row
  if new, `joinOrganization`, the roles, the invitation accepted, the change log. Never links on
  email alone. `/user/web` runs with no tenant declared, so the lookup is a projection by email, and
  each link sets `TenantContext` to that invitation's organization. `InvitationSyncJob` polls
  pending invitations, marks expired ones, and marks one accepted by a different address and removes
  that provider membership.
- Tests: `InvitationAcceptanceIT` with the fake: exact match, different address, unverified, second
  organization for an existing user, concurrent first requests, expired, revoked.

**Step 19. Close the old paths.**

- Builds: Removes `projectInviteCode/*`, `/api/v1/invitation/web` and both `joinOrganization` HTTP
  routes, with their changesets dropping the table. `POST /auth/register` answers only when the
  adapter has the self-registration extra. `POST /organization/web` requires a platform
  administrator when `echno.identity.self-service-organizations` is false, the WorkOS default.
- Tests: `PublicEndpointTenantExposureTest` stays green; routes gone; `docs/openapi.json`
  regenerated.

**Step 20. First organization.**

- Builds: `OrganizationProvisioning` is extracted from `OrganizationService.addOrganization`:
  organization, templates, finance seeding, with no current user required. `BootstrapRunner`
  (section 7).
- Tests: `BootstrapIT`: fresh database to invitation sent; rerun sends nothing; rerun after expiry
  with no admin sends a new one.

**Step 21. Minimum app version.**

- Builds: `GET /api/v1/public/client-config`, returning the provider, its public client ids and the
  minimum Android and iOS versions from settings. Reaches no tenant data, which
  `PublicEndpointTenantExposureTest` proves. Dark until mobile reads it.

### Core and web

**Step 22. Core, additive release.**

- Builds: `invitation` types, service and hooks for the new endpoints.
  `access/{types,services,hooks}` from core #158 and #159, without groups, and without the mutation
  hooks nothing uses. Old invite-code exports stay until step 32 so web can move in its own time.
  Public API snapshot and request contract updated, version bumped.
- Tests: per-layer tests, `every-write-call-has-a-route`.

**Step 23. Web sign-in module.**

- Builds: Opens `auth-workos` in `echno-web` with CI on it. `lib/auth/server.ts` (`getSession`,
  `getAccessToken`, `authProxy`) and `lib/auth/client.tsx` (`useAuthSession`, `startSignIn`,
  `signOut`). About 25 files move to them; `signIn('keycloak')` goes. An ESLint rule bans
  `next-auth` and `@workos-inc/*` imports outside `lib/auth`. Behaviour identical.
- Tests: the existing `lib/auth` suites, plus the lint rule. This is the one large web PR; it is
  mechanical and has no logic change, which is what makes it reviewable.

**Step 24. WorkOS sign-in in web.**

- Builds: `lib/auth/workos/*` using `authkit-nextjs`: `app/callback/route.ts`, `authkit` composed
  into the existing `proxy.ts` so CSP handling stays, `withAuth()` for the BFF's bearer token,
  `signOut`. `AUTH_PROVIDER` chosen at runtime and passed from the server layout, since a client
  component cannot read a server variable. `validateAuthConfig` checks the chosen provider's
  variables. The redirect is read on the server at run time and passed as `redirectUri` to `authkit`,
  and its origin as `baseURL` to `handleAuth`, since the container's hostname differs from the public
  one. The image build never receives `NEXT_PUBLIC_WORKOS_REDIRECT_URI`, because a value present at
  build is frozen in. A sign-in route is the Initiate login URI (F13). `form-action` in `lib/csp.ts` gains the WorkOS sign-out host, because the current
  `'self'` would block the redirect. The idle rule (`lib/auth/session-idle.ts`) keeps working
  through a small signed activity cookie, since `wos-session` is not ours to write to; WorkOS's
  inactivity timeout sits behind it. The frontchannel-logout route answers only in Keycloak mode.
  `X-Organization-Id` and the organization selector stay untouched, which is the cheap step that
  keeps WorkOS's organization switching out of web and core.
- Tests: sign-in and sign-out flow with a mocked WorkOS; idle deadline; BFF forwards the token; CSP
  test; an image built with the redirect variable unset that sends the run-time value to WorkOS.
- Dark: only `AUTH_PROVIDER=workos` reaches it.

**Step 25. Invitation screens.**

- Builds: The invitation form and table move to the new core service: email, roles, project per
  role, manager, state, resend, revoke. `join-organization-form`, `invitation-qr-code`,
  `/organizations/join` and `/register` go. The onboarding page tells someone with no organization
  to ask their administrator. Web moves to the new core version.
- Tests: form, states, onboarding page. Component tests for a role that refuses a project.

**Step 26. End to end on WorkOS (Gate A).**

- Builds: A local stack with no Keycloak container against the WorkOS staging environment, from a
  runbook: bootstrap, first admin accepts, invites a second person, second person signs in, role
  change seen on next request, removal seen on next request, same-domain address refused, sign-out.
  Test addresses stay outside `example.com` (F24). Acceptance starts from the invitation email's link
  and ends through the web callback, since the library enforces PKCE on every callback and this flow
  is the one not yet seen working. Results recorded on #914. This is the go-live gate for the backend, core and web. Keycloak mode is
  run the same way against the local compose file, as the regression proof.

### Matrix screen and areas

**Step 27. Screen read endpoints.**

- Builds: `AccessAdminReader`, `CurrentUserAccessControllerWeb`, the catalog with enforced flags,
  without groups, with `AccessAdminReader.user()` fixed to load one person.
- Tests: cross-org tests from `access-endpoints`.

**Step 28. Screen write endpoints.**

- Builds: `AccessAdminWriter` rewritten on `AccessRoleAssignments`, with rules 1 to 8 of design note
  2.6. Keeps `RequestedGrants` and the "handler action matches its method" test from the first
  attempt.
- Tests: one per rule, section 4.

**Step 29. Roles & Permissions screen (Gate B).**

- Builds: Web #522 and #524 folded into one PR on the web branch, with the review's spec points:
  Roles and Users tabs only, right-side details panel, only enforced options shown, gated on
  `access:view`. Then web #523 (`can()`) as its own PR. Core version bumped.

**Step 30. One area per PR.**

- Builds: Each area swaps guards to `@access.can`, applies `access.filter` in its list and get
  queries, registers as scope-aware, and carries the equivalence test (design note 2.5). Start with
  `assets`, since its branch exists, then the areas the four roles with no counterpart need
  (`finance`, `purchase-orders`, `indents`, `vendors`, then `projects`), then the rest in the order
  of the mapping note. The audit's G-04 (commented-out guard), G-06 (attachments), G-08 (finance)
  and G-14 are fixed in the owning area's PR, with the owner's yes where they change access.

**Step 31. Remaining role-name queries.**

- Builds: Manager listings, attendance geofence, `EmployeeDto.orgRoles`.

**Step 32. Remove the old role code.**

- Builds: `OrgRole`, `employee_org_roles`, `LegacyRoleMirror`, the Keycloak role endpoints, the
  token's permission claim, the dead `hasAuthority` guards. Core drops its old exports in a major
  release. Roles with counterparts can now take a project. The Keycloak adapter keeps writing the
  `system-admin` subgroup for `ensureAdminMfa` until the stagings migrate.

**Step 33. Drop `users_table.keycloak_id`.**

- Builds: Once step 9 has run on every environment.

### Mobile

Not needed for Gate A: the first production deployment signs in through web. The apps' repos are not
local to this workstation, so these steps are sized from their file lists only.

**Step 34. Android sign-in module.**

- Builds: `org.echno.core.auth` behind one interface, the minimum version check against step 21. No
  provider change.

**Step 35. iOS sign-in module.**

- Builds: The same for `Sources/EchnoKit/Auth`.

**Step 36. WorkOS in the apps.**

- Builds: One PR per app: authorize and exchange against WorkOS with the existing PKCE code, a
  custom scheme redirect, refresh with no secret (F20, rechecked on this Application), `AccessTokenClaims`
  reduced to what the app needs, roles read from the backend.

### Not in this plan

These come later: the staging migration to WorkOS, webhooks (F14), a machine credential, and Echno's own
tokens, whose triggers are in the identity note section 8.

## 7. A brand-new production organization

There is no Keycloak, no self-registration, no platform administrator and no user yet.

1. WorkOS environment configured (section 8). Sign-up off, so a person can only arrive by invitation.
2. The backend starts with `ECHNO_BOOTSTRAP_ORG_NAME`, `ECHNO_BOOTSTRAP_ORG_EMAIL` and
   `ECHNO_BOOTSTRAP_ADMIN_EMAIL` set. `BootstrapRunner`, under the lock row, does nothing if the
   organization email already has an active System Admin. Otherwise it creates the organization and its
   roles if missing, writes `organization_identity`, and creates an invitation for that email holding
   System Admin with a 30 day expiry. `identity_task` then creates the WorkOS organization and sends the
   invitation.
3. **Email: WorkOS sends it.** The backend has no mail sender today and needs none here, since WorkOS
   sends invitations, password resets and verification ([Invitations](https://workos.com/docs/authkit/invitations)).
   The custom email domain (F9) is what makes delivery dependable.
4. The person follows the link, sets a name and password on the hosted page, and is sent to web's
   callback. Accepting promptly counts as email verification
   ([Invitations](https://workos.com/docs/authkit/invitations)).
5. Web calls `GET /user/web`; step 18 links the account and they hold System Admin. They invite everyone
   else from the screen in step 25.
6. The bootstrap variables come out of the environment at the next deploy. Left in, they do nothing,
   because an active System Admin exists.

Starting further organizations on a stack is staff-only: a platform administrator row, added by
runbook, and `POST /organization/web`.

**Billing.** `requireCreateOrganizationEntitlement` needs a current user, so the runner calls
`OrganizationProvisioning` below that check. Module entitlements behave as they do today.

## 8. What the client stack needs

The interface the deployment workstream builds against: environment variables and secrets, with the
secrets marked. Everything under "Removed" stops existing in a stack with `workos` selected.

### Backend

| Variable | Secret | Value |
| --- | --- | --- |
| `ECHNO_IDENTITY_PROVIDER` | no | `workos` |
| `WORKOS_API_KEY` | yes | The environment's API key |
| `WORKOS_CLIENT_ID` | no | The Application's client id. Signing keys are derived from it. |
| `ECHNO_JWT_ACCEPTED_ISSUERS` | no | The exact `iss` from a decoded token, read after the custom auth domain is live |
| `ECHNO_JWT_AUDIENCE` | no | The `aud` value set in the JWT template |
| `ECHNO_CORS_ALLOWED_ORIGINS` | no | The web origin |
| `ECHNO_BOOTSTRAP_ORG_NAME`, `ECHNO_BOOTSTRAP_ORG_EMAIL`, `ECHNO_BOOTSTRAP_ADMIN_EMAIL` | no, but kept with secrets because they name a person | First boot only |
| `ECHNO_MIN_APP_VERSION_ANDROID`, `ECHNO_MIN_APP_VERSION_IOS` | no | Optional until mobile ships |

### Web

| Variable | Secret | Value |
| --- | --- | --- |
| `AUTH_PROVIDER` | no | `workos` |
| `WORKOS_API_KEY` | yes | Same key as the backend, or a second one for this app if the environment allows |
| `WORKOS_CLIENT_ID` | no | Same as the backend |
| `WORKOS_COOKIE_PASSWORD` | yes | Generated, at least 32 characters |
| `NEXT_PUBLIC_WORKOS_REDIRECT_URI` | no | `https://<web host>/callback`. Set at run time only and never at image build. The library does not read `WORKOS_REDIRECT_URI` (F13). |
| `WORKOS_ISSUER` | no | Same value as `ECHNO_JWT_ACCEPTED_ISSUERS`. Without it, `authkit-nextjs` does not check the issuer. |
| `ECHNO_SESSION_ACTIVITY_SECRET` | yes | Generated, signs the activity cookie |
| `BACKEND_API_URL` | no | As today |

### WorkOS environment settings

Done once per client stack, by hand or by script, and written into the deployment runbook:
one project per client stack (F7); sign-up off (F11); automatic domain membership and SSO provisioning
off (F12); redirect URI, sign-out URI, Initiate login URI and invitation URL set for the web host; JWT template with the
`aud`; access token 5 minutes; inactivity timeout and maximum session length matching web's idle rule;
MFA per the answer in section 10.

### DNS

Three CNAME records for the custom email domain (F9). One for the custom auth domain (F16). Both verify
within 72 hours, so do them days before go-live. `ECHNO_JWT_ACCEPTED_ISSUERS` changes when the auth
domain goes live.

### Network

Backend and web need outbound HTTPS to `api.workos.com`. No inbound path is needed for WorkOS at go-live.

### Removed

The Keycloak container and its database, all `KEYCLOAK_*` and `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_*`
variables, `NEXTAUTH_*`, the Keycloak seed, the client secret verification task (replaced by the backend
health indicator from step 16), the auth host and its admin gate, Keycloak backup and restore steps, and
Keycloak's scrape targets and alerts. The deployment roles need an `identity_provider` variable so
stagings keep all of this unchanged.

### Backups

Account credentials now live at WorkOS, outside our backups. Echno's database holds who belongs where
and the identity rows, so a restore keeps access correct as long as the WorkOS environment survives.

## 9. What is reusable from the first attempt

The review on #904 judged the engine and guard sound, and I agree. Taking code from those branches is
expected; each piece goes through its step's PR and review.

**Reuse, with the review's fixes**

| Code | Branch | Fix when it comes across |
| --- | --- | --- |
| `modules/access/api/*`, `AccessControlModule`, `ModuleManifest.core`, `ModuleRegistry`, `AccessServiceContract` | `access-control-module` | `AccessFilter.admits` checks the organization; other-org record in the contract |
| `001`, `003`, `004`, `005` changesets, `AccessRole*`, `AccessEmployee*`, `AccessPartyLink`, repositories, `AccessFactsQueries` | `access-control-tables` | Project column; organization predicate in fact queries; indexes |
| `MatrixAccessService`, `DatabaseAccessFactsSource`, `CachedAccessFactsSource`, `CachedHoldings`, `AccessFactsCacheConfiguration` | `access-control-engine` | Null status is inactive; per-request memo; after-commit eviction |
| `AccessGuard`, `CurrentAccessSubject`, `AccessCatalog` | `access-control-guard` | False on a malformed key; subject through `user_identity`; enforced flag derived from the scan |
| `AccessAdminReader`, `CurrentUserAccessControllerWeb`, read DTOs, the cross-org tests | `access-endpoints` | Groups out; `user()` loads one person |
| `RequestedGrants`, "only grant what you hold", the handler action test | `access-write-endpoints` | None |
| Core `access/*` (#158, #159); web #522, #524, #523 | web and core branches | The web review's list |
| The proxy fix `93b3a0e2` | web | Already asked for as its own PR into `development` |

**Do not reuse**

- `StandardRole`, `StandardRoles`, `StandardRoleSeeder`: the wrong role set, held in Java, every role
  marked system. Templates in tables replace them.
- Everything for groups and departments: `002-access-groups`, `AccessGroup*`, `AccessMembersRequest`,
  `/groups`, the screen tab.
- `MatrixRoleAssignments`: writes straight to Keycloak and has no mirror. `AccessRoleAssignments`
  replaces it.
- `AccessAdminWriter`: 500 lines written without the rules, so a rewrite on the new assignment service is
  smaller than a repair. Its tests are a useful checklist.
- `access-assets`: the conversion pattern is worth reading, but it has no scope filtering. Redo it at
  step 30.
- The `docker-compose.dev.yml` and `build.gradle` changes on `access-control-module`, and any committed
  `docs/openapi.json` from `access-write-endpoints`. Regenerate the snapshot instead.

**Hrishi's audit** (ClickUp 14zdkkvx5u5) is used for facts. I read `01-architecture`, `07-default-roles`,
`08-contextual-access` and `09-gap-analysis`. Its G-03 finding, that the `hasAuthority('resource:scope')`
guards are held by nobody, is why no guard depends on the Keycloak subgroups after step 11. It agrees with
`docs/org-scoped-roles.md`, and the mapping note's check of one token from each staging still has to pass
before `auth-workos` reaches `development`.

## 10. Questions

For me, with my default:

- **Where the first production image comes from.** Default: build it from `auth-workos` at Gate A,
  merging `development` into `auth-workos` as it moves. `development` and the stagings wait until the
  full migration, as the design note says. Promoting at Gate A would put steps 10 to 13 on both stagings
  at the moment the first production deployment needs attention.
- **MFA.** Keycloak requires it for System Admins only, and AuthKit has no per-role setting (F15).
  Default: require it through the organization policy for the first organization, since everyone there is
  new and enrols at first sign-in. The cost is TOTP for every site user.

For the owner, on the ticket:

- **Go-live with the five roles that work.** System Admin, Project Manager, Site Engineer, QA/QC Engineer
  and Store Keeper have today's access from Gate A. Management Director, Procurement Manager, Finance
  Manager and Client / Owner hold only the baseline until he signs off their cells and their areas convert
  (step 30). Default: go live at Gate A if the organization can start with the five; otherwise the
  `finance` and procurement areas become part of the gate.
- The remaining items in design note section 7.
