# Echno Backend

This repository contains the source code for the Echno Backend

## Description

The Echno Backend provides a comprehensive set of features for managing employees, projects, tasks, attendance, inventory, and more. It is built with a modern technology stack and includes robust security and monitoring capabilities.

## Features

*   **User Management:** Secure user authentication and authorization using JWT.
*   **Project and Task Management:** Create and manage projects, assign tasks, and track progress.
*   **Employee Management:** Maintain employee records and track attendance.
*   **Inventory Management:** Manage materials, track inventory transactions, and handle goods received notes.
*   **Issue Tracking:** Report and manage issues related to projects or tasks.
*   **PDF Generation:** Generate PDF reports for various modules.
*   **Monitoring:** Integrated with Prometheus and Grafana for monitoring application metrics.
*   **Logging:** Centralized logging with Loki.

## Technologies Used

*   **Backend:** Java 21, Spring Boot 3
*   **Database:** PostgreSQL
*   **Authentication:** Spring Security with JWT (connected to Keycloak)
*   **Build Tool:** Gradle
*   **API Documentation:** OpenAPI (Swagger)
*   **Monitoring:** Prometheus, Grafana
*   **Logging:** Loki
*   **Containerization:** Docker

## Getting Started

### Prerequisites

*   Java 21
*   Gradle 8+
*   PostgreSQL
*   Docker (optional, for running in a container)
*   Keycloak (or any other OpenID Connect provider)

### Installation

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/echno_backend.git
    cd echno_backend
    ```

2.  **Configure the application:**

    Create an `application-local.yml` file in `src/main/resources` and override the necessary properties from `application.yml`. At a minimum, you will need to configure the database connection and the JWT issuer URI but currently keycloak is disabled for testing so issuer-uri wound be needed.

    ```yaml
    spring:
      datasource:
        url: jdbc:postgresql://localhost:5432/your-database
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

You can run the application using the following command:

```bash
./gradlew bootRun
```

The application will be available at `http://localhost:8080`.

### Running with Docker

You can also run the application using Docker. First, build the Docker image:

```bash
docker build -t echno-backend .
```

Then, run the Docker container:

```bash
docker run -p 8080:8080 echno-backend
```

## Configuration

The main configuration file is `src/main/resources/application.yml`. You can override the default configuration by creating an `application-local.yml` file in the same directory or by setting environment variables.

## Monitoring

The application exposes metrics in Prometheus format at `/actuator/prometheus`. You can use the provided `prometheus.yml` and `grafana-provisioning` to set up a monitoring stack.

## Contributing

Contributions are welcome! Please feel free to submit a pull request.

## License

This project is licensed under the MIT License.
