# Identity provider independence

Status: option C (Echno's own tokens) approved by the reviewer on 2026-10-30. The owner decided on
the ticket that Echno moves to WorkOS and does not continue with Keycloak. Target repos: `echno-backend`, `echno-core`,
`echno-web`, `echno-android`, `echno-ios`.
Scope: what it takes to switch the identity provider (Keycloak today, WorkOS next, possibly another
later) without rebuilding Echno each time. It covers the benefits, the difficulties, the security
concerns and the long-term impact of each way of doing it, and recommends one. It builds on
[Access Matrix and Identity Provider](2026-10-30-access-matrix-and-identity-provider.md), section 3.

## 1. The goal

A provider switch should mean: write or configure one adapter, move the user accounts, change
settings, and, only if accounts can't be copied in one go (section 10), run both providers side by
side until everyone has moved. It should not mean changing
the access rules, the database model of users and organizations, or every client app.

Two jobs are kept apart:

- **Authentication** (who is this person): the identity provider's job. Passwords, MFA, single
  sign-on with a customer's own directory, account recovery.
- **Authorization** (what may they do, in which organization): Echno's job, in the access matrix.

## 2. Where we are

| Part | Tied to the provider? | Today |
| --- | --- | --- |
| Roles and permissions | No, once the matrix lands | Role names live in Keycloak subgroups `/org-{id}/{role}` and in `employee_org_roles`. The matrix moves them into Echno only. |
| Backend calls to the provider | No, once the port lands | `KeycloakUserProvisioningService`, `KeycloakGroupService` and others are called directly. The planned `IdentityProvider` port and its adapters put them behind one interface. |
| User records | **Yes** | `users_table.keycloak_id` is required and unique. Every user is identified by their Keycloak id. 55 Java files mention Keycloak. |
| Organization records | **Yes** | An organization is a Keycloak group. The port returns an `ExternalOrgId`, but nothing stores it per provider yet. |
| Token checks in the backend | **Yes** | The JWT decoder trusts the Keycloak realm, and `JwtAuthConverter` reads Keycloak's `groups` claim. |
| Web sign-in | **Yes** | next-auth's `KeycloakProvider`, with Keycloak's issuer and client id, and Keycloak-specific session and refresh handling. |
| Android sign-in | **Yes** | `KeycloakTokenService` in `org.echno.core.auth`. |
| iOS sign-in | **Yes** | Its own PKCE flow against the `realms/echno-realm` address. |

So the plan as it stands makes the **backend's calls** switchable, but a switch would still break
every user link and need a release of all three apps.

## 3. Changes every option needs

These hold whichever option in section 4 is chosen.

1. **An identity table.** `user_identity(provider, issuer, subject, user_id)`, unique on issuer and
   subject, because a token is matched by the issuer that signed it, and one provider can have more
   than one issuer (a customer's own single sign-on, for example). Echno's own user id is the
   identity everywhere else; `keycloak_id` goes. The same for organizations:
   `organization_identity(provider, provider_org_id, organization_id)`. A switch adds rows instead
   of rewriting users.
2. **Echno holds the truth about membership.** Which organizations a person belongs to is read from
   `Employee`, never from the provider's token. The provider's own organizations, where it has
   them, follow Echno's records. Events the provider sends (a WorkOS webhook that someone joined or
   left, for example) are notices, not commands: Echno checks each one against its own records and
   acts only where it matches, such as an accepted invitation for a pending employee. Each event is
   handled once, by its id, and in any order. An event with nothing to match is logged and ignored,
   so nobody joins an organization except through Echno's invitation.
3. **One contract every adapter passes.** An `IdentityProviderContract` test, like
   `AccessServiceContract`, run against each adapter and an in-memory fake. The fake also serves
   tests and local runs. One setting picks the adapter: `echno.identity.provider`.
4. **A small port, with extras outside it.** The port holds only what every provider can do: users,
   memberships, invitations, password reset, and turning a token into a person. Features only one
   provider has (WorkOS single sign-on and directory sync, Keycloak's authorization services) are
   optional extras the app checks for, never part of the port.
5. **A switch plan.** For a period, trust tokens from both providers, each matched by its issuer to
   its own row in `user_identity`. Then move the users, by importing password hashes where the new
   provider accepts the old format, or else by sending each person a set-password link.

## 4. The options for the apps

### Option A: backend port only (the plan as written)

The backend calls the provider through the port. The apps keep signing in to the provider directly.

**Benefits**
- The least work now. The apps don't change until the switch.
- Each app can use the provider's own sign-in kit, with its full feature set.

**Difficulties**
- A switch needs a new release of web, Android and iOS, timed together, and mobile releases wait on
  app store review and on users updating.
- Each app holds its own sign-in code per provider. Three code bases to change and test per switch.
- During the switch, old app versions still sign in to the old provider, so it can't be turned off
  until every user has updated.

**Security concerns**
- The provider's tokens reach the apps, so a stolen token works against the provider as well as
  Echno.
- Each app's sign-in code is separate security code to keep right: PKCE, token storage, refresh,
  logout. Three chances for a mistake.

**Long-term impact**
- Every switch, and every provider-side change to its sign-in flow, costs three app changes.
- In practice this keeps us on the current provider, because leaving is expensive.

### Option B: configurable sign-in in each app

Each app uses a standard OpenID Connect client. The provider's address and client id are settings
the app reads at start, not code.

**Benefits**
- A switch for providers that follow the standard closely is a settings change, not a code change.
- Uses well-tested libraries: next-auth's generic OIDC provider, AppAuth on Android and iOS.

**Difficulties**
- Providers differ in practice: claim names, logout, refresh token rules, organization selection.
  Each difference needs handling in all three apps, so most switches still need app changes.
- WorkOS's AuthKit works best through its own kits. Using it as a plain OIDC provider has to be
  proven before relying on it.
- Old app versions with the old settings still need the old provider during the switch.

**Security concerns**
- The same as option A: provider tokens reach the apps, and three apps each hold sign-in code.
- Settings fetched at start must come only from Echno's own servers, or a tampered setting could send
  people to a fake sign-in page.

**Long-term impact**
- Cheaper switches than option A, but not free. Each provider's quirks accumulate in three places.

### Option C: Echno issues its own tokens (chosen)

The apps sign in through Echno's backend. The backend sends the person to the provider's sign-in
page, checks the result, and gives the app an **Echno token**. The apps and the rest of the backend
only ever see Echno tokens. The provider is visible only inside the backend's adapter.

**Benefits**
- A switch touches only the backend: a new adapter and settings. No app release.
- Old app versions keep working through a switch, because they talk to Echno, not the provider.
- One place for sign-in rules: session length, refresh, logout, revoking every session of a person
  who leaves an organization.
- Echno's tokens carry exactly what Echno needs (user id, session, and how the person signed in),
  the same whichever provider is behind them. The organization is not in the token: the app names it
  in the `X-Organization-Id` header and Echno checks it on every request (design note 3.1).
- Running two providers side by side is internal to the backend.

**Difficulties**
- The most work now. The backend gains a sign-in flow, a token service with signing keys, refresh
  and logout. All three apps change their sign-in once.
- Echno becomes an OAuth authorization server for its own apps. That has to be built carefully, and
  reviewed as security code.
- Signing keys need rotation and safe storage in the deployment vault.
- A bug in Echno's token service locks everyone out, so it needs good tests and monitoring.

**Security concerns**
- **Signing keys** are the highest-value secret. They live in the vault, rotate on a schedule, and
  are published as a key set so a rotation doesn't break sessions.
- **Tokens stay short-lived**, with refresh tokens stored only server side (web) or in the
  platform's secure storage (mobile), rotated on every use, and revoked on logout or removal.
- **The redirect back from the provider** is checked strictly: exact redirect addresses, `state`
  and PKCE on every sign-in, so a sign-in can't be hijacked.
- **Account linking** must never join accounts on email alone. A new provider account links to an
  Echno user only through a verified path: an invitation for that email, or a sign-in to the old
  provider in the same session. Otherwise someone who creates an account with another person's
  email at a new provider could take over their Echno account.
- **MFA and single sign-on** stay the provider's job. Each adapter maps the provider's answer to
  how the person signed in (password, MFA, single sign-on), and the Echno token carries it. Where
  the answer is missing or unreadable, Echno counts it as the weakest, so an organization that
  requires MFA refuses the sign-in rather than letting it through.
- **Provider tokens are never stored** longer than the sign-in needs, and never reach the apps.

**Long-term impact**
- Switching becomes a backend project measured in weeks, not a release train across five repos.
- We can use more than one provider at once, for example WorkOS for customers who want their own
  single sign-on and a simpler provider for everyone else.
- Pricing and vendor changes are less risky, because leaving a provider is affordable.
- We own more security code. That is a lasting cost: reviews, key rotation, keeping libraries up to
  date.

## 5. Comparison

| | A: backend port only | B: configurable sign-in | C: Echno tokens |
| --- | --- | --- | --- |
| Work now | Least | Medium | Most |
| Work per switch | All three apps | Some app changes | Backend only |
| Old app versions during a switch | Break | Mostly break | Keep working |
| Provider tokens in the apps | Yes | Yes | No |
| Sign-in code to secure | Three apps | Three apps, shared libraries | One backend |
| Two providers at once | Hard | Hard | Built in |
| New security code we own | Little | Little | A token service and signing keys |

## 6. Cost of deferring option C

If we follow the plan as written (option A) and build Echno tokens later, the token service costs
about the same whenever it is built. The extra cost is everything done twice.

**What it costs to do later**

1. **A second sign-in rewrite in all three apps.** Moving to WorkOS under option A means web,
   Android and iOS each replace their Keycloak sign-in with WorkOS's. Moving to Echno tokens later
   replaces it again: the same app work twice, tested and security-reviewed twice.
2. **A second coordinated release and changeover.** Each sign-in change needs web, Android and iOS
   released together, and old app versions keep signing in the old way until people update. Doing C
   later means a second period of keeping both ways working, and probably forcing an update.
3. **WorkOS-specific habits to undo.** WorkOS puts the chosen organization in its token and
   switches organizations through its own refresh. If the apps and `echno-core` are built around
   that, it has to be unpicked later, along with WorkOS's logout and session rules in each app.
4. **A cost that grows.** Everything that starts accepting provider tokens adds to the later move:
   a new app, an integration, a worker service, a customer's API client.
5. **Everyone signs in again twice.** Moving to Echno tokens ends every session once. Done with the
   WorkOS move, people sign in again once; done separately, twice.

**What is not lost:** the access matrix, the port and its backend adapters, the identity tables,
membership in Echno and the contract tests. Option C builds on all of them unchanged.

**If we defer, four cheap steps now keep the later cost to the token service and one app change**

- Make the changes in section 3 now, especially the identity tables instead of `keycloak_id` and
  membership read only from Echno. These are the expensive ones to retrofit, because every user
  record changes.
- Keep the choice of organization in Echno, not in the provider's token. The apps tell Echno which
  organization they are working in, so WorkOS's organization switching never reaches `echno-core`
  or web.
- In each app, keep sign-in behind one small module (web's `auth.ts`, Android's
  `org.echno.core.auth`, iOS's `AuthSession`), so replacing it later is a local change.
- Add a minimum app version check, so an update can be required when the sign-in changes.

**When it is cheapest:** together with the WorkOS move. That move already forces app releases, a
period with both providers running (section 10) and everyone signing in again. Doing both together
pays each of those once; doing C later pays them twice.

## 7. Security concerns for any option

- **Issuer confusion.** When two providers are trusted at once, each token is matched by its issuer
  to that provider's keys and identity rows. A token from one can never be read as a user of the
  other.
- **Audience checks.** Every token is checked for Echno's audience, so a token issued for another
  application is refused.
- **Removal takes effect at once.** Making an employee inactive revokes their sessions and refresh
  tokens. The access matrix already gives an inactive person nothing (design note 2.8).
- **Password moves.** Hashes are moved only over a secure channel, never logged, and deleted from
  any export once imported. Where a format can't be imported, people set a new password through a
  link, never a password sent to them.
- **Audit.** Sign-ins, links between accounts and provider switches are recorded, in the same spirit
  as the access change log.

## 8. Decision and phases

Option C, approved by the reviewer. Because the move to WorkOS is decided, option C lands together
with it (section 6), so the apps change their sign-in once and everyone signs in again once. Each
phase ships on its own:

1. The common changes in section 3: identity tables, membership in Echno, the contract test and
   fake adapter, the small port. These help every option and come right after the port (design
   note delivery steps 8 and 9).
2. The Echno token service in the backend, used first by web, which already signs in through its
   own server, so the change is smallest there (step 15).
3. Android and iOS move to Echno sign-in (step 16).
4. The WorkOS adapter, then the move of every account: copied in one go where WorkOS accepts
   Keycloak's password format, otherwise with both providers side by side for a set period (section
   10) (step 17).

Between phases 2 and 3, web uses Echno tokens while released Android and iOS builds still send
Keycloak tokens. The backend accepts both, each checked against its own issuer and mapped through
`user_identity` to the same Echno user, and nothing else. Provider tokens from WorkOS never reach the
API, because WorkOS sits behind Echno's sign-in. Keycloak tokens stop being accepted once the
minimum app version (section 6) rules out the old builds, and before Keycloak is turned off.

## 9. What changes in each repo

| Repo | Change |
| --- | --- |
| `echno-backend` | Identity tables replace `keycloak_id`; the port's contract test and fake; with option C, the sign-in flow, the token service, key rotation and session revocation. |
| `echno-core` | With option C, the sign-in and refresh calls point at Echno's endpoints; the token's contents change. |
| `echno-web` | next-auth uses Echno as its provider instead of `KeycloakProvider`. |
| `echno-android` | `KeycloakTokenService` is replaced by a sign-in against Echno. |
| `echno-ios` | The PKCE flow points at Echno instead of the Keycloak realm. |

## 10. Open questions

- Which provider features do we expect to need per customer (single sign-on, directory sync, MFA),
  so the optional extras are planned rather than added late?
- Who owns the signing keys and their rotation in the deployment repo?
- **Moving people's accounts to the new provider.** Passwords live in the provider, not in Echno,
  so a switch means recreating every account at the new provider. There are two ways:
  1. **Copy everyone across in one go.** If the new provider accepts the old one's stored password
     format (passwords are stored scrambled, and the new provider must support the old provider's
     method), everyone moves at once and nobody notices. Nothing runs side by side. Check this
     first: whether WorkOS accepts Keycloak's format.
  2. **Move people as they sign in.** If the passwords can't be copied, each person is moved at their
     next sign-in: they sign in to the old provider one last time, and their account is created at
     the new one, or they set a new password there. Both providers run side by side meanwhile.

  The second way leaves two business decisions:
  - **How long both run.** Running two providers costs money and upkeep and means two systems to
    secure, so it needs an end date, for example 30 or 90 days.
  - **What happens to people who miss it.** Someone on leave or who rarely signs in may not sign in
    before the end date. Their old password then goes with the old provider. The fallback is an
    email with a set-password link for the new provider, or a fresh invitation from their
    administrator (design note 3.4).
