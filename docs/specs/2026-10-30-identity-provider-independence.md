# Identity provider independence

Status: decided 2026-11-07. Option A (backend port with adapters, apps sign in with the provider).
Option C (Echno's own tokens) is deferred, with its triggers in section 8. The owner decided on the
ticket that Echno moves to WorkOS. Target repos: `echno-backend`, `echno-core`, `echno-web`,
`echno-android`, `echno-ios`.
Scope: what it takes to switch the identity provider (Keycloak today, WorkOS next, possibly another
later) without rebuilding Echno each time. It covers the benefits, the difficulties, the security
concerns and the long-term impact of each way of doing it. It builds on
[Access Matrix and Identity Provider](2026-10-30-access-matrix-and-identity-provider.md), section 3.
The steps are in the [WorkOS build plan](2026-11-07-workos-build-plan.md).

## 1. The goal

A provider switch should mean: write or configure one adapter, move the user accounts, change
settings, and, only if accounts can't be copied in one go (section 10), run both providers side by
side until everyone has moved. It should not mean changing the access rules or the database model of
users and organizations. Under option A a switch still means one change in each app, kept small by
the sign-in module in section 6.

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
   left, for example) are notices only: Echno checks each one against its own records and
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

### Option A: backend port only (chosen)

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
the app reads at start.

**Benefits**
- A switch for providers that follow the standard closely needs only a settings change.
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

### Option C: Echno issues its own tokens (deferred, see section 8)

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

We follow option A and may build Echno tokens later. The token service costs about the same whenever
it is built. The extra cost is everything done twice. Section 8 explains why that cost is small today.

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

**Because we defer, these four cheap steps keep the later cost to the token service and one app
change. All four are in the build plan.**

- Make the changes in section 3 now, especially the identity tables instead of `keycloak_id` and
  membership read only from Echno. These are the expensive ones to retrofit, because every user
  record changes.
- Keep the choice of organization in Echno, not in the provider's token. The apps tell Echno which
  organization they are working in, so WorkOS's organization switching never reaches `echno-core`
  or web.
- In each app, keep sign-in behind one small module (web's `auth.ts`, Android's
  `org.echno.core.auth`, iOS's `AuthSession`), so replacing it later is a local change.
- Add a minimum app version check, so an update can be required when the sign-in changes.

**When it is cheapest:** together with the WorkOS move, for an installed base. That move forces app
releases, a period with both providers running (section 10) and everyone signing in again, and doing
both together pays each of those once. The first production deployment has no installed base, so those
costs do not arise for it.

## 7. Security concerns for any option

- **Issuer confusion.** When two providers are trusted at once, each token is matched by its issuer
  to that provider's keys and identity rows. A token from one can never be read as a user of the
  other.
- **Audience checks.** Every token is checked for Echno's audience, so a token issued for another
  application is refused. WorkOS session tokens carry no `aud` by default, so our JWT template sets
  one (build plan, F1).
- **Removal takes effect at once.** Making an employee inactive ends their access on the next request,
  because membership is read from Echno on every request. Sessions and refresh tokens are revoked
  after that, as a follow-up. The access matrix already gives an inactive person nothing (design note
  2.8).
- **Password moves.** Hashes are moved only over a secure channel, never logged, and deleted from
  any export once imported. Where a format can't be imported, people set a new password through a
  link, never a password sent to them.
- **Audit.** Sign-ins, links between accounts and provider switches are recorded, in the same spirit
  as the access change log.

## 8. Decision and phases

**Option A, with option C deferred.** The history: I advised option C at first, and an earlier version
of this note recorded that advice as an approval. On 2026-11-07 I changed my mind (comment on #914).

Why option C waits:

1. **Risk at the worst moment.** Option C makes Echno an OAuth authorization server. We would own
   signing keys, key rotation, refresh token rotation and revocation, and one bug in that code locks
   everyone out. The first production deployment should not also be the first run of that code.
2. **The double-migration cost is small today.** Section 6 is mostly about moving existing people twice
   and keeping old app builds working. The first production deployment is a brand-new organization
   with no existing users, and no client has an account or a mobile build on the old sign-in, so there
   is nothing to move.
3. **The cheap steps protect us.** The four steps in section 6 keep the later move down to the token
   service and one app change, and we are doing all four now.

**We revisit option C when** a customer needs a second provider, or the mobile apps have enough
installed users that a provider change gets expensive to coordinate.

One setting, `echno.identity.provider`, picks the adapter per environment, and each environment trusts
exactly one provider. Each phase ships on its own:

1. The common changes in section 3 and the four cheap steps: identity tables, membership in Echno, the
   contract test and fake adapter, the small port, then the Keycloak adapter. Both stagings run on it
   with no change in behaviour.
2. The WorkOS adapter, and the backend's invitation and first-organization flows.
3. Web signs in with WorkOS behind its sign-in module, chosen by the same setting. The first production
   deployment goes live here, with no Keycloak in its stack.
4. Android and iOS sign in with the provider, behind their own modules, with the minimum app version
   check.
5. Later: the stagings move to WorkOS, by the route in section 10.

The [build plan](2026-11-07-workos-build-plan.md) holds the PR-sized steps. Trusting two providers at
once (section 3, point 5) is built only if the staging move needs it.

## 9. What changes in each repo

| Repo | Change |
| --- | --- |
| `echno-backend` | Identity tables replace `keycloak_id`; the port, its contract test and fake; the Keycloak and WorkOS adapters; invitations and the first-organization runner; a client config endpoint with the minimum app version. |
| `echno-core` | Invitation and access clients. Sign-in does not touch core: it never sees a provider, and the organization stays in `X-Organization-Id`. |
| `echno-web` | All sign-in behind `lib/auth`, with next-auth for Keycloak and `authkit-nextjs` for WorkOS. |
| `echno-android` | `org.echno.core.auth` behind one interface, the version check, then PKCE against WorkOS. |
| `echno-ios` | `AuthSession` behind one interface, the version check, then PKCE against WorkOS. |

## 10. Open questions

- Which provider features do we expect to need per customer (single sign-on, directory sync, MFA),
  so the optional extras are planned rather than added late?
- **Moving people's accounts to the new provider.** This applies to the later staging move; the first
  production deployment has nobody to move. Passwords live in the provider,
  so a switch means recreating every account at the new provider. There are two ways:
  1. **Copy everyone across in one go.** If the new provider accepts the old one's stored password
     format (passwords are stored scrambled, and the new provider must support the old provider's
     method), everyone moves at once and nobody notices. Nothing runs side by side. WorkOS's import
     takes `pbkdf2` in PHC form and does not name Keycloak
     ([password formats](https://workos.com/docs/migrate/other-services)), so this looks possible and
     needs a proof with one real hash.
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
