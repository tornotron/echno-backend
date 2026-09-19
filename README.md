# Echno Backend

REST API and business core for Echno, the Echno construction management platform. This service is the
source of truth: it exposes the HTTP API, owns the domain logic, and persists application data.

## Description

The Echno Backend provides a comprehensive set of features for managing employees, projects, tasks,
attendance, inventory, and construction finance. It is built with Java 21 and Spring Boot 3, secured with
Keycloak-issued JWTs, and ships with OpenAPI documentation plus Prometheus, Grafana and Loki
observability.

## Features

* **User management:** authentication and authorization using Keycloak-issued JWT access tokens, with
  tenant-scoped roles.
* **Project and task management:** create and manage projects, break work down (WBS), assign tasks, and
  track progress.
* **Employee management:** maintain employee records, roles, attendance and leave.
* **Inventory management:** manage materials, record inventory transactions, and handle goods received
  notes (GRN) against purchase orders.
* **Construction finance:** vendor and progress invoices, payments, and a double-entry ledger (chart of
  accounts, journal entries, customers, financial reports).
* **Issue tracking:** report and manage issues related to projects or tasks.
* **PDF generation:** generate PDF reports for various modules.
* **Multi-tenancy:** every request is scoped to a tenant, enforced fail-closed at the data layer.
* **Monitoring and logging:** Prometheus metrics, Grafana dashboards, and centralized logging with Loki.

## Technologies Used

* **Backend:** Java 21, Spring Boot 3
* **Application database:** CockroachDB (PostgreSQL wire protocol). Keycloak uses a separate PostgreSQL
  instance; the application itself talks to CockroachDB.
* **Authentication:** Spring Security resource server with JWT, backed by Keycloak (OpenID Connect)
* **Build tool:** Gradle
* **API documentation:** OpenAPI (springdoc), served through Swagger UI
* **Object storage:** DigitalOcean Spaces (S3 compatible) for attachments
* **Monitoring:** Prometheus, Grafana
* **Logging:** Loki
* **Containerization:** Docker

## Architecture and module map

The code is organized by domain module under `src/main/java/org/tornotron/echno_backend`. Each module
holds its own domain entities, DTOs, mappers, repositories, services and web controllers. The deeper
design notes for the major modules live in [`docs/`](docs):

| Area | Package | Reference |
|------|---------|-----------|
| Construction finance (invoices, payments, ledger, reports) | `finance` | [finance-module-mvp-guide.md](docs/finance-module-mvp-guide.md) |
| Attendance | `attendance` | [ATTENDANCE_MODULE.md](docs/ATTENDANCE_MODULE.md), [usage guide](docs/ATTENDANCE_MODULE_USAGE_GUIDE.md) |
| Leave | `leave` | [LEAVE_MODULE.md](docs/LEAVE_MODULE.md) |
| Work breakdown structure | `wbs` | [WBS_MODULE.md](docs/WBS_MODULE.md) |
| Inventory and goods receipts | `inventoryTransaction`, `goodsReceivedNote`, `material` | [PROJECT_LEVEL_INVENTORY.md](docs/PROJECT_LEVEL_INVENTORY.md), [Goods Managment Summary](docs/Goods%20Managment%20Summary) |
| Multi-tenancy | `common`, `aspect` | [MULTI_TENANCY_FILTER_GUIDE.md](docs/MULTI_TENANCY_FILTER_GUIDE.md) |
| Organization-scoped roles | `organization`, `auth` | [org-scoped-roles.md](docs/org-scoped-roles.md) |
| Caching | (cross-cutting) | [REDIS_CACHING_GUIDE.md](docs/REDIS_CACHING_GUIDE.md) |
| Database operations | (CockroachDB) | [cockroachdb-ops-handbook.md](docs/cockroachdb-ops-handbook.md) |
| Scaling | (cross-cutting) | [BACKEND_SCALING_PLAN.md](docs/BACKEND_SCALING_PLAN.md) |

Once the service is running, the live, generated API reference is available at `/swagger-ui.html`
(OpenAPI JSON at `/v3/api-docs`). Controllers and DTOs carry OpenAPI annotations, so the Swagger UI
groups endpoints by module and documents request and response fields.

Those paths are closed to unauthenticated callers by default, because the OpenAPI document is the
whole endpoint surface in one file. Set `SWAGGER_PUBLIC_ACCESS=true` to open them for an environment
where the docs are wanted, such as your own run:

```bash
SWAGGER_PUBLIC_ACCESS=true ./gradlew bootRun
```

## Getting Started

### Prerequisites

* Java 21 (the Gradle wrapper fetches Gradle itself)
* Docker with the Compose plugin, for the local dependency stack and the Testcontainers suite
* `curl` and `jq` for the first-token walk-through below

### Local development

The backend needs four services: CockroachDB (the application database), Keycloak (the identity
provider), an S3-compatible object store and Redis. `docker-compose.dev.yml` starts all four at
the versions the staging runs, on localhost-only ports offset from the defaults so the stack can
share a machine with anything holding the standard ones:

| Service | Image | Port on localhost | Dev-only credential |
|---------|-------|-------------------|---------------------|
| CockroachDB | `cockroachdb/cockroach:v26.2.4`, single node, insecure | 27257 (SQL), 28080 (admin UI) | user `root`, no password, database `echno` |
| Keycloak | `quay.io/keycloak/keycloak:26.0.7`, dev mode | 8180 | console `admin` / `admin`; realm `echno-realm` from `dev/keycloak/echno-realm.json` |
| MinIO | `quay.io/minio/minio:RELEASE.2024-11-07T00-52-20Z` | 9100 (S3 API), 9101 (console) | `echno-dev` / `echno-dev-secret`; buckets `echno-dev`, `echno-datasets` |
| Redis | `redis:7.4-alpine` | 6380 | none |

`src/main/resources/application-local.yml` is the `local` profile and points at exactly this
stack. Every credential in the compose file, the realm import and the profile is a public
dev-only literal that exists on no deployed instance; the compose file and `dev/` are excluded
from the image by `.dockerignore`.

1.  **Start the stack** and wait for the healthchecks:
    ```bash
    ./gradlew devUp
    ```
    Keycloak imports the realm on its first start (about half a minute). `./gradlew devLogs`
    follows the service logs. The import carries the realm, the three clients, the role
    catalogue and the dev accounts; service accounts and authorization services on the backend
    client are switched on by the backend itself on its first boot, because Keycloak 26.0.x
    fails a startup import that already carries them.

2.  **Run the backend** with the `local` profile:
    ```bash
    ./gradlew bootRun --args='--spring.profiles.active=local'
    ```
    Liquibase creates the schema on the first run, which takes a few minutes on CockroachDB
    (about three hundred changesets, each its own DDL transaction); later starts take seconds.
    Once the context is up the backend
    reconciles the realm: it adds the `echno-admin` and `echno-service` accounts, the composite
    job roles, the `echno-web-local` client and the `echno-local-dev` account (password
    `echno-dev`) with its own organization and employee record, so that account can call any
    organization-scoped endpoint straight away. The API answers on `http://localhost:8080`,
    the OpenAPI UI on `http://localhost:8080/swagger-ui.html`, and `/actuator/health` and
    `/actuator/info` on the same port.

3.  **Get a first token.** The realm carries a public `echno-dev-cli` client with the password
    grant enabled, for the command line only, whose tokens carry the backend's audience:
    ```bash
    TOKEN=$(curl -s -X POST \
      http://localhost:8180/realms/echno-realm/protocol/openid-connect/token \
      -d grant_type=password -d client_id=echno-dev-cli \
      -d username=echno-local-dev -d password=echno-dev | jq -r .access_token)
    ```
    The realm also carries one account per organization role, `dev-system-admin`,
    `dev-org-manager`, `dev-hr-admin`, `dev-project-manager`, `dev-qa-engineer`,
    `dev-safety-officer`, `dev-site-engineer`, `dev-store-keeper` and
    `dev-observation-producer`, all with password `echno-dev`. Roles are organization-scoped
    (see `docs/org-scoped-roles.md`), so these accounts can sign in but hold no role until an
    organization admin invites them and assigns one; `echno-local-dev` is the account with an
    organization out of the box.

4.  **Make a first call** with the token:
    ```bash
    curl -s http://localhost:8080/api/v1/employee/web/lookup \
      -H "Authorization: Bearer $TOKEN" | jq .
    ```
    The dev Keycloak console is at `http://localhost:8180` and the MinIO console at
    `http://localhost:9101`, with the credentials from the table.

5.  **Stop the stack** and delete its volumes when done:
    ```bash
    ./gradlew devDown
    ```

To move a port, set the matching `ECHNO_DEV_*_PORT` variable for compose (see the top of
`docker-compose.dev.yml`) and change the same port in `application-local.yml`. Values you do not
want to commit go in a further profile file such as `application-local-me.yml`, which
`.gitignore` already covers; activate it alongside with `--spring.profiles.active=local,local-me`.

### Build and test

```bash
./gradlew build
```

The suite runs against a CockroachDB container through Testcontainers, so Docker has to be
available. `docs/openapi.json` is verified by the build and regenerated with
`./gradlew openApiSnapshot -PupdateOpenApiSnapshot`; see "The API contract".

## Build and Run

### Running the application

Against the local stack, see "Local development" above. Against any other environment, every
`${...}` placeholder in `application.yml` has to be supplied as an environment variable, which is
how the deployments run it (the variable contract lives in the `echno-deployment` repository).

`GET /actuator/info` reports the running revision: the git branch, sha and commit time the
build was made from, and the build time and version. The Gradle build writes both files it reads
(`git.properties`, `META-INF/build-info.properties`); an image build receives the commit through
the `GIT_SHA` and `GIT_BRANCH` build arguments because the build context carries no `.git`.

### Running with Docker

Build the image:

```bash
docker build -t echno-backend .
```

Run the container:

```bash
docker run -p 8080:8080 echno-backend
```

## Configuration

The main configuration file is `src/main/resources/application.yml`. Override the defaults by creating an
`application-local.yml` in the same directory or by setting environment variables.

## The API contract

The OpenAPI document is committed at [`docs/openapi.json`](docs/openapi.json). It is the published
contract: clients read it, and `tornotron/echno-core` checks the field names it sends against its
schemas. Nothing generates code from it on either side, so a renamed or removed field produces no
compile error anywhere downstream, and a diff on this file is the only warning a client gets.

It is generated from the running application rather than written by hand:

```bash
./gradlew openApiSnapshot                          # fail if the committed copy has drifted
./gradlew openApiSnapshot -PupdateOpenApiSnapshot  # regenerate it
```

Regenerate and commit it in the same pull request as any change to a request or response DTO. CI
runs the check on every pull request, so a stale copy fails the build rather than going unnoticed.

The task needs Docker (it starts CockroachDB through Testcontainers, as the test suite does) and
runs in a JVM of its own, which is why it is not part of `test` or `check`.

### Saying that a field may be null

A field that can come back null is marked on the DTO:

```java
@Schema(description = "Finance customer the project is billed to. Null when no client is set.",
        example = "6b1e9c22-9f8a-4a1b-9c0e-1d2f3a4b5c6d", nullable = true)
private UUID customerId;
```

The document is OpenAPI 3.1, where that is written as a type union rather than a `nullable` flag,
so the property comes out as `"type": ["string", "null"]`. swagger-core reads the annotation but
does not translate it under 3.1, so `NullableSchemaCustomizer` does the conversion and
`OpenApiNullabilityTest` fails if an annotated field is not described that way in the committed
document. Before that existed the annotation produced no output at all, which is why nothing in
the document was marked nullable for as long as it has been published.

Mark only what you have checked. A field the document declares non-null is a field a client is
entitled to parse without a guard, so leaving one unmarked is recoverable and marking one wrongly
is not.

### Saying what a field will be rejected for

Validation constraints on a DTO are published, so writing one is also writing the contract:

```java
@NotBlank @Size(max = 1000) String reason
```

comes out as `"minLength": 1, "maxLength": 1000` with `reason` in the schema's `required`.

Left to swagger-core it does not. `required` is written from `@NotNull` and nothing else, so a
`@NotBlank` field read as optional; `@Size` assigns `minLength` after `@NotBlank` has set it and
its `min` defaults to 0, so the pairing above published `minLength: 0`, which positively permits
the empty string the endpoint refuses; and `@Positive`, `@PositiveOrZero`, `@Negative`,
`@NegativeOrZero` and `@Email` were not read at all. `ValidationConstraintsModelConverter` fills
those in and `OpenApiValidationConstraintsTest` fails when an annotated field is not described.

It states only what the annotation itself guarantees. `@NotBlank` also rejects a string of blanks
and `minLength: 1` does not say so, which is deliberate: a document that understates a constraint
costs a client one rejected request, and one that overstates it makes the client refuse input the
server would have taken. `@Min`, `@Max`, `@DecimalMin`, `@DecimalMax`, `@Size` and `@Pattern` are
already published correctly and are left alone; `@Past`, `@Future`, `@Digits`, cross-field rules
and constraints naming validation groups have no keyword that means what they mean, so the
document says nothing about them.

Two nested types with the same simple name cannot both be published, and the loser is silently
replaced by the winner rather than reported. Give one of them a name of its own with
`@Schema(name = "...")` when that happens.

Serving the document from a deployment is a separate decision: `/v3/api-docs` and the Swagger UI
answer 401 unless `SWAGGER_PUBLIC_ACCESS=true` is set for that environment.

## Monitoring

The application exposes metrics in Prometheus format at `/actuator/prometheus`. Use the provided
`prometheus.yml` and `grafana-provisioning` to set up a monitoring stack.

## Contributing

Contributions are welcome. Please open a pull request.

## License

This project is planned for release under the GNU Affero General Public License v3.0 (AGPL-3.0).
