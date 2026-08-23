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

## Getting Started

### Prerequisites

* Java 21
* Gradle 8+
* CockroachDB (the application database)
* Keycloak (or another OpenID Connect provider) for authentication
* Docker (optional, for running in a container)

### Installation

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/tornotron/echno-backend.git
    cd echno-backend
    ```

2.  **Configure the application:**

    Create an `application-local.yml` file in `src/main/resources` and override the properties you need
    from `application.yml`. At a minimum, configure the database connection and the JWT issuer URI.
    CockroachDB speaks the PostgreSQL wire protocol, so the standard PostgreSQL JDBC driver and URL are
    used:

    ```yaml
    spring:
      datasource:
        url: jdbc:postgresql://localhost:26257/echno?sslmode=disable
        username: your-username
        password: your-password
      security:
        oauth2:
          resourceserver:
            jwt:
              issuer-uri: <your-keycloak-issuer-uri>
    ```

3.  **Build the application:**
    ```bash
    ./gradlew build
    ```

## Build and Run

### Running the application

```bash
./gradlew bootRun
```

The application will be available at `http://localhost:8080`.

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

## Monitoring

The application exposes metrics in Prometheus format at `/actuator/prometheus`. Use the provided
`prometheus.yml` and `grafana-provisioning` to set up a monitoring stack.

## Contributing

Contributions are welcome. Please open a pull request.

## License

This project is planned for release under the GNU Affero General Public License v3.0 (AGPL-3.0).
