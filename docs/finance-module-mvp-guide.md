# Finance Module MVP — Complete Implementation Guide

**Stack:** Spring Boot 3.3.x · Java 21 · CockroachDB · Flyway · MapStruct · Testcontainers
**Target:** A working, auditable double-entry finance module shippable in ~2 weeks
**Scope:** Chart of Accounts → Journal Entries → Customers → Invoices → Payments → Reports (Trial Balance, P&L, Balance Sheet)

This document is meant to be followed top to bottom. Every code file shows its full path. Every design choice has a "why." If you copy this end-to-end you'll have a functioning module.

---

## Table of Contents

1. [Prerequisites & Setup](#1-prerequisites--setup)
2. [Project Skeleton](#2-project-skeleton)
3. [Foundation Layer (BaseEntity, Money, Exceptions, Security)](#3-foundation-layer)
4. [Phase 1 — Ledger Core (Accounts + Journal Entries)](#4-phase-1--ledger-core)
5. [Phase 2 — Customer Master](#5-phase-2--customer-master)
6. [Phase 3 — Invoices](#6-phase-3--invoices)
7. [Phase 4 — Payments](#7-phase-4--payments)
8. [Phase 5 — Reports (TB, P&L, Balance Sheet)](#8-phase-5--reports)
9. [CockroachDB-Specific Concerns](#9-cockroachdb-specific-concerns)
10. [Testing Strategy](#10-testing-strategy)
11. [Operational Concerns (Docs, Logging, Health)](#11-operational-concerns)
12. [Day-by-Day Checklist](#12-day-by-day-checklist)
13. [Common Pitfalls](#13-common-pitfalls)
14. [v2 Roadmap](#14-v2-roadmap)

---

## 1. Prerequisites & Setup

### 1.1 Tools you need installed

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Use Temurin or Oracle |
| Maven | 3.9+ | Or Gradle if you prefer; examples here use Maven |
| Docker | latest | For local CockroachDB and Testcontainers |
| CockroachDB | v23.2+ | Local single-node or CockroachDB Serverless |
| IDE | IntelliJ recommended | Enable annotation processing for Lombok + MapStruct |

### 1.2 Run CockroachDB locally

The simplest path is Docker:

```bash
docker run -d \
  --name=roach1 \
  -p 26257:26257 -p 8080:8080 \
  -v "${PWD}/cockroach-data:/cockroach/cockroach-data" \
  cockroachdb/cockroach:latest start-single-node --insecure
```

Create the database:

```bash
docker exec -it roach1 ./cockroach sql --insecure -e "CREATE DATABASE finance;"
```

The DB Console is at <http://localhost:8080>. SQL endpoint at `localhost:26257`.

### 1.3 Why CockroachDB changes some defaults

- **Use UUIDs as primary keys.** CockroachDB is a distributed SQL database; sequential `BIGSERIAL` keys cause range hotspots. Use `UUID` with `gen_random_uuid()`.
- **Use `org.hibernate.dialect.CockroachDialect`.** Hibernate 6 ships this; it knows about CockroachDB's `AS OF SYSTEM TIME`, `unique_rowid()`, and other quirks.
- **Handle transaction retry errors.** CockroachDB uses serializable isolation by default and may abort transactions with SQLSTATE `40001`. You must retry. We'll add a small interceptor.
- **`SELECT ... FOR UPDATE` works**, but is rarely needed because SERIALIZABLE makes conflicts detectable at commit. Use it sparingly.
- **Time-travel queries.** `AS OF SYSTEM TIME` lets you read consistent snapshots from the past — very useful for reports.

---

## 2. Project Skeleton

### 2.1 Generate the project

Use [start.spring.io](https://start.spring.io) with: **Maven, Java 21, Spring Boot 3.3.x**, dependencies: **Spring Web, Spring Data JPA, Spring Security, Validation, Flyway, PostgreSQL Driver, Lombok**.

You'll add MapStruct, Testcontainers, and springdoc-openapi manually.

### 2.2 `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.company.app</groupId>
    <artifactId>finance-module</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>finance-module</name>

    <properties>
        <java.version>21</java.version>
        <mapstruct.version>1.6.3</mapstruct.version>
        <lombok.version>1.18.34</lombok.version>
        <testcontainers.version>1.20.3</testcontainers.version>
        <springdoc.version>2.6.0</springdoc.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- DB -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- MapStruct -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
            <version>${mapstruct.version}</version>
        </dependency>

        <!-- OpenAPI docs -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>cockroachdb</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                        <path>
                            <groupId>org.mapstruct</groupId>
                            <artifactId>mapstruct-processor</artifactId>
                            <version>${mapstruct.version}</version>
                        </path>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok-mapstruct-binding</artifactId>
                            <version>0.2.0</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2.3 `application.yml`

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: finance-module

  datasource:
    url: jdbc:postgresql://localhost:26257/finance?sslmode=disable
    username: root
    password: ""
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
      max-lifetime: 1800000

  jpa:
    open-in-view: false   # critical — never true in production
    hibernate:
      ddl-auto: validate  # Flyway owns the schema
    properties:
      hibernate:
        dialect: org.hibernate.dialect.CockroachDialect
        jdbc:
          batch_size: 50
          order_inserts: true
          order_updates: true
        format_sql: true
    show-sql: false

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

server:
  port: 8080
  error:
    include-message: always
    include-binding-errors: always

logging:
  level:
    root: INFO
    com.company.app.finance: DEBUG
    org.hibernate.SQL: DEBUG   # turn off in prod
    org.hibernate.orm.jdbc.bind: TRACE  # turn off in prod

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
```

### 2.4 Package structure (feature-first)

```
src/main/java/com/company/app/finance/
├── FinanceModuleApplication.java
├── config/
│   ├── SecurityConfig.java
│   ├── JpaAuditingConfig.java
│   └── OpenApiConfig.java
├── shared/
│   ├── domain/
│   │   └── BaseEntity.java
│   ├── exception/
│   │   ├── FinanceException.java
│   │   ├── UnbalancedEntryException.java
│   │   ├── InvalidJournalException.java
│   │   ├── AccountNotFoundException.java
│   │   ├── PeriodClosedException.java
│   │   ├── DuplicateIdempotencyKeyException.java
│   │   └── GlobalExceptionHandler.java
│   ├── money/
│   │   └── MoneyUtils.java
│   └── numbering/
│       ├── EntryNumberGenerator.java
│       └── DocumentSequence.java
├── ledger/
│   ├── domain/
│   │   ├── Account.java
│   │   ├── AccountType.java
│   │   ├── JournalEntry.java
│   │   ├── JournalEntryLine.java
│   │   └── JournalStatus.java
│   ├── repository/
│   │   ├── AccountRepository.java
│   │   └── JournalEntryRepository.java
│   ├── dto/
│   │   ├── AccountDto.java
│   │   ├── CreateAccountRequest.java
│   │   ├── JournalEntryDto.java
│   │   ├── JournalEntryLineDto.java
│   │   ├── PostJournalRequest.java
│   │   └── ReverseJournalRequest.java
│   ├── mapper/
│   │   ├── AccountMapper.java
│   │   └── JournalEntryMapper.java
│   ├── service/
│   │   ├── AccountService.java
│   │   └── JournalPostingService.java
│   └── web/
│       ├── AccountController.java
│       └── JournalEntryController.java
├── master/
│   └── customer/
│       ├── domain/Customer.java
│       ├── domain/Address.java
│       ├── repository/CustomerRepository.java
│       ├── dto/...
│       ├── service/CustomerService.java
│       └── web/CustomerController.java
├── invoice/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   ├── service/
│   └── web/
├── payment/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   ├── service/
│   └── web/
└── report/
    ├── dto/
    ├── service/ReportService.java
    └── web/ReportController.java

src/main/resources/db/migration/
├── V1__init_schema.sql
├── V2__seed_chart_of_accounts.sql
├── V3__customer.sql
├── V4__invoice.sql
└── V5__payment.sql

src/test/java/com/company/app/finance/
├── AbstractIntegrationTest.java
├── ledger/...
├── invoice/...
└── report/...
```

### 2.5 Main application class

`src/main/java/com/company/app/finance/FinanceModuleApplication.java`:

```java
package com.company.app.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FinanceModuleApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceModuleApplication.class, args);
    }
}
```

---

## 3. Foundation Layer

### 3.1 BaseEntity with auditing

Every persistent entity extends this. It gives you `createdBy`, `createdAt`, `updatedBy`, `updatedAt`, and optimistic locking via `@Version`.

`shared/domain/BaseEntity.java`:

```java
package com.company.app.finance.shared.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
```

### 3.2 Enable JPA auditing

`config/JpaAuditingConfig.java`:

```java
package com.company.app.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
                return Optional.of("system");
            }
            return Optional.of(auth.getName());
        };
    }
}
```

### 3.3 Money utilities

`shared/money/MoneyUtils.java`:

```java
package com.company.app.finance.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyUtils {

    public static final int SCALE = 4;             // store at 4 dp
    public static final int DISPLAY_SCALE = 2;     // present at 2 dp
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyUtils() {}

    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        return value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal forDisplay(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(DISPLAY_SCALE, ROUNDING);
        return value.setScale(DISPLAY_SCALE, ROUNDING);
    }

    public static boolean isZero(BigDecimal v) {
        return v == null || v.signum() == 0;
    }

    public static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    public static boolean isNegative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }

    public static BigDecimal sum(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            if (v != null) total = total.add(v);
        }
        return normalize(total);
    }
}
```

**Why scale 4 not 2?** Tax calculations and percentage discounts produce fractional cents. Storing at 4 dp preserves precision through intermediate calculations. We round to 2 dp only for display.

### 3.4 Exception hierarchy

`shared/exception/FinanceException.java`:

```java
package com.company.app.finance.shared.exception;

public abstract class FinanceException extends RuntimeException {
    protected FinanceException(String message) { super(message); }
    protected FinanceException(String message, Throwable cause) { super(message, cause); }
    public abstract String getCode();
}
```

`shared/exception/UnbalancedEntryException.java`:

```java
package com.company.app.finance.shared.exception;

import java.math.BigDecimal;

public class UnbalancedEntryException extends FinanceException {
    public UnbalancedEntryException(BigDecimal totalDebit, BigDecimal totalCredit) {
        super("Journal entry is unbalanced: debits=" + totalDebit + " credits=" + totalCredit);
    }
    @Override public String getCode() { return "JE_UNBALANCED"; }
}
```

`shared/exception/InvalidJournalException.java`:

```java
package com.company.app.finance.shared.exception;

public class InvalidJournalException extends FinanceException {
    public InvalidJournalException(String message) { super(message); }
    @Override public String getCode() { return "JE_INVALID"; }
}
```

`shared/exception/AccountNotFoundException.java`:

```java
package com.company.app.finance.shared.exception;

import java.util.UUID;

public class AccountNotFoundException extends FinanceException {
    public AccountNotFoundException(UUID id) { super("Account not found: " + id); }
    public AccountNotFoundException(String code) { super("Account not found with code: " + code); }
    @Override public String getCode() { return "ACCOUNT_NOT_FOUND"; }
}
```

`shared/exception/PeriodClosedException.java`:

```java
package com.company.app.finance.shared.exception;

import java.time.LocalDate;

public class PeriodClosedException extends FinanceException {
    public PeriodClosedException(LocalDate date) {
        super("Cannot post to closed period for date: " + date);
    }
    @Override public String getCode() { return "PERIOD_CLOSED"; }
}
```

`shared/exception/DuplicateIdempotencyKeyException.java`:

```java
package com.company.app.finance.shared.exception;

public class DuplicateIdempotencyKeyException extends FinanceException {
    private final String existingResourceId;
    public DuplicateIdempotencyKeyException(String key, String existingResourceId) {
        super("Idempotency key already used: " + key);
        this.existingResourceId = existingResourceId;
    }
    public String getExistingResourceId() { return existingResourceId; }
    @Override public String getCode() { return "IDEMPOTENCY_CONFLICT"; }
}
```

### 3.5 Global exception handler

`shared/exception/GlobalExceptionHandler.java`:

```java
package com.company.app.finance.shared.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        Map<String, Object> details
    ) {
        public static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message, Instant.now(), Map.of());
        }
        public static ErrorResponse of(String code, String message, Map<String, Object> details) {
            return new ErrorResponse(code, message, Instant.now(), details);
        }
    }

    @ExceptionHandler(UnbalancedEntryException.class)
    public ResponseEntity<ErrorResponse> handleUnbalanced(UnbalancedEntryException ex) {
        log.warn("Unbalanced entry: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler({InvalidJournalException.class, PeriodClosedException.class})
    public ResponseEntity<ErrorResponse> handleInvalidJournal(FinanceException ex) {
        log.warn("Invalid journal request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler({AccountNotFoundException.class, EntityNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        String code = ex instanceof FinanceException fe ? fe.getCode() : "NOT_FOUND";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(code, ex.getMessage()));
    }

    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateIdempotency(DuplicateIdempotencyKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(ex.getCode(), ex.getMessage(),
                Map.of("existingResourceId", ex.getExistingResourceId())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> details = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of("VALIDATION_FAILED", "Request validation failed", details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of("CONSTRAINT_VIOLATION", ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("CONCURRENT_MODIFICATION",
                "Resource was modified concurrently. Reload and retry."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("DATA_INTEGRITY",
                "Operation violates a database constraint"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse.of("ACCESS_DENIED", "Insufficient permissions"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
```

### 3.6 Security skeleton

For the MVP, use HTTP Basic with in-memory users. Replace with your actual auth (JWT / OAuth2) later. The key piece is **role definitions** — your business code must reference them.

`config/SecurityConfig.java`:

```java
package com.company.app.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())  // we use Basic / token auth, not session cookies
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(b -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService users(PasswordEncoder encoder) {
        UserDetails clerk = User.builder()
            .username("clerk").password(encoder.encode("clerk"))
            .roles("FINANCE_CLERK").build();
        UserDetails approver = User.builder()
            .username("approver").password(encoder.encode("approver"))
            .roles("FINANCE_CLERK", "FINANCE_APPROVER").build();
        UserDetails admin = User.builder()
            .username("admin").password(encoder.encode("admin"))
            .roles("FINANCE_CLERK", "FINANCE_APPROVER", "FINANCE_ADMIN").build();
        return new InMemoryUserDetailsManager(clerk, approver, admin);
    }
}
```

**Role meaning:**

- `FINANCE_CLERK`: create drafts (draft invoices, draft journals), view data
- `FINANCE_APPROVER`: issue invoices, post journals, record payments
- `FINANCE_ADMIN`: chart of accounts management, reversals

### 3.7 OpenAPI config

`config/OpenApiConfig.java`:

```java
package com.company.app.finance.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI financeOpenAPI() {
        return new OpenAPI()
            .info(new Info().title("Finance Module API").version("0.1.0")
                .description("Double-entry accounting, invoicing, payments, and reports"))
            .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
            .components(new Components().addSecuritySchemes("basicAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }
}
```

### 3.8 Document numbering — `EntryNumberGenerator`

We need gapless sequential numbers per document type per fiscal year (JE-2026-000001, INV-2026-000001, PAY-2026-000001). We'll use a small DB table and atomic increments — safe under concurrency.

`shared/numbering/DocumentSequence.java`:

```java
package com.company.app.finance.shared.numbering;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "document_sequences",
    uniqueConstraints = @UniqueConstraint(columnNames = {"doc_type", "fiscal_year"}))
@Getter @Setter
public class DocumentSequence {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @Column(name = "doc_type", nullable = false, length = 10)
    private String docType;        // JE, INV, PAY

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;        // 2026 means FY 2025-26 in India (Apr-Mar)

    @Column(name = "next_value", nullable = false)
    private long nextValue;

    @Version
    private Long version;
}
```

`shared/numbering/EntryNumberGenerator.java`:

```java
package com.company.app.finance.shared.numbering;

import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class EntryNumberGenerator {

    private final DocumentSequenceRepository repo;

    /**
     * Generates the next document number for a given type, using the current fiscal year.
     * Indian fiscal year runs April–March, so anything before April rolls back a year.
     * Format: {prefix}-{fiscalYear}-{6-digit-padded number}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(String docType) {
        int fy = currentFiscalYear(LocalDate.now());
        DocumentSequence seq = repo.findByDocTypeAndFiscalYearForUpdate(docType, fy)
            .orElseGet(() -> {
                DocumentSequence s = new DocumentSequence();
                s.setDocType(docType);
                s.setFiscalYear(fy);
                s.setNextValue(1L);
                return repo.save(s);
            });
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        repo.save(seq);
        return "%s-%d-%06d".formatted(docType, fy, value);
    }

    static int currentFiscalYear(LocalDate date) {
        // FY2026 = April 2025 – March 2026 (Indian convention)
        if (date.getMonth().getValue() >= Month.APRIL.getValue()) {
            return date.getYear() + 1;
        }
        return date.getYear();
    }
}

interface DocumentSequenceRepository extends JpaRepository<DocumentSequence, java.util.UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DocumentSequence s WHERE s.docType = :type AND s.fiscalYear = :fy")
    Optional<DocumentSequence> findByDocTypeAndFiscalYearForUpdate(String type, int fy);
}
```

**Why `REQUIRES_NEW`?** If the outer transaction rolls back, you still want to consume the number rather than reuse it after a partial failure. Pick your poison: REQUIRES_NEW gives gaps on failure but never duplicates; default propagation gives no gaps but slightly higher chance of duplicates if you mis-handle exceptions. For audit compliance, gapless is preferred — but in the MVP, REQUIRES_NEW + a "void / cancel" flow is simpler. We'll start here and revisit.

### 3.9 Flyway migration — base schema

`src/main/resources/db/migration/V1__init_schema.sql`:

```sql
-- Document sequences for gapless numbering per fiscal year
CREATE TABLE document_sequences (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    doc_type    VARCHAR(10)  NOT NULL,
    fiscal_year INT          NOT NULL,
    next_value  BIGINT       NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (doc_type, fiscal_year)
);
```

That's the foundation. Now the real work begins.

---


## 4. Phase 1 — Ledger Core

This is the foundation of the whole module. Get this right and everything else is mechanical.

### 4.1 The double-entry rules you must enforce

1. Every journal entry has at least 2 lines.
2. Sum of debits = sum of credits.
3. Each line is either a debit OR a credit, never both, never zero on both.
4. Amounts are positive.
5. POSTED entries are immutable. To correct, reverse (creates an offsetting entry) and post a new one.
6. Account types follow normal-balance rules:
   - ASSET, EXPENSE → increase with **debit**
   - LIABILITY, EQUITY, INCOME → increase with **credit**

### 4.2 Enums

`ledger/domain/AccountType.java`:

```java
package com.company.app.finance.ledger.domain;

public enum AccountType {
    ASSET(true),
    LIABILITY(false),
    EQUITY(false),
    INCOME(false),
    EXPENSE(true);

    private final boolean debitNormal;

    AccountType(boolean debitNormal) {
        this.debitNormal = debitNormal;
    }

    public boolean isDebitNormal() {
        return debitNormal;
    }

    /** Sign convention: returns +1 if a debit increases this account, -1 if a credit does. */
    public int normalSign() {
        return debitNormal ? 1 : -1;
    }
}
```

`ledger/domain/JournalStatus.java`:

```java
package com.company.app.finance.ledger.domain;

public enum JournalStatus {
    DRAFT,
    POSTED,
    REVERSED   // marked when a reversal entry exists pointing back here
}
```

### 4.3 Entities

`ledger/domain/Account.java`:

```java
package com.company.app.finance.ledger.domain;

import com.company.app.finance.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "accounts",
    uniqueConstraints = @UniqueConstraint(name = "uk_accounts_code", columnNames = "code"))
@Getter @Setter
@NoArgsConstructor
public class Account extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Account parent;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 500)
    private String description;
}
```

`ledger/domain/JournalEntry.java`:

```java
package com.company.app.finance.ledger.domain;

import com.company.app.finance.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entries",
    indexes = {
        @Index(name = "idx_je_entry_date", columnList = "entry_date"),
        @Index(name = "idx_je_status", columnList = "status"),
        @Index(name = "idx_je_reference", columnList = "reference")
    },
    uniqueConstraints = @UniqueConstraint(name = "uk_je_number", columnNames = "entry_number"))
@Getter @Setter
@NoArgsConstructor
public class JournalEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entry_number", nullable = false, length = 30)
    private String entryNumber;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 100)
    private String reference;     // e.g. invoice number, payment number

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JournalStatus status;

    @Column(name = "reversed_by_entry_id")
    private UUID reversedByEntryId;

    @Column(name = "reverses_entry_id")
    private UUID reversesEntryId;

    @Column(name = "source_type", length = 30)
    private String sourceType;    // INVOICE, PAYMENT, MANUAL, REVERSAL

    @Column(name = "source_id")
    private UUID sourceId;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true,
        fetch = FetchType.LAZY)
    private List<JournalEntryLine> lines = new ArrayList<>();

    public void addLine(JournalEntryLine line) {
        line.setJournalEntry(this);
        this.lines.add(line);
    }
}
```

`ledger/domain/JournalEntryLine.java`:

```java
package com.company.app.finance.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "journal_entry_lines",
    indexes = {
        @Index(name = "idx_jel_account", columnList = "account_id"),
        @Index(name = "idx_jel_journal", columnList = "journal_entry_id")
    })
@Getter @Setter
@NoArgsConstructor
public class JournalEntryLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(length = 500)
    private String narration;

    @Column(name = "line_order", nullable = false)
    private int lineOrder;
}
```

### 4.4 Flyway migration — ledger tables

`src/main/resources/db/migration/V2__ledger.sql`:

```sql
CREATE TABLE accounts (
    id           UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    code         VARCHAR(20)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    parent_id    UUID,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    description  VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    version      BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_accounts_code UNIQUE (code),
    CONSTRAINT fk_accounts_parent FOREIGN KEY (parent_id) REFERENCES accounts(id),
    CONSTRAINT ck_accounts_type CHECK (type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE'))
);

CREATE TABLE journal_entries (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    entry_number          VARCHAR(30)  NOT NULL,
    entry_date            DATE         NOT NULL,
    description           VARCHAR(500) NOT NULL,
    reference             VARCHAR(100),
    status                VARCHAR(20)  NOT NULL,
    reversed_by_entry_id  UUID,
    reverses_entry_id     UUID,
    source_type           VARCHAR(30),
    source_id             UUID,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_je_number UNIQUE (entry_number),
    CONSTRAINT ck_je_status CHECK (status IN ('DRAFT','POSTED','REVERSED'))
);

CREATE INDEX idx_je_entry_date ON journal_entries(entry_date);
CREATE INDEX idx_je_status ON journal_entries(status);
CREATE INDEX idx_je_reference ON journal_entries(reference);
CREATE INDEX idx_je_source ON journal_entries(source_type, source_id);

CREATE TABLE journal_entry_lines (
    id                UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    journal_entry_id  UUID           NOT NULL,
    account_id        UUID           NOT NULL,
    debit             DECIMAL(19,4)  NOT NULL DEFAULT 0,
    credit            DECIMAL(19,4)  NOT NULL DEFAULT 0,
    narration         VARCHAR(500),
    line_order        INT            NOT NULL DEFAULT 0,

    CONSTRAINT fk_jel_journal  FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id),
    CONSTRAINT fk_jel_account  FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT ck_jel_debit_nonneg  CHECK (debit  >= 0),
    CONSTRAINT ck_jel_credit_nonneg CHECK (credit >= 0),
    CONSTRAINT ck_jel_xor CHECK (
        (debit > 0 AND credit = 0) OR (debit = 0 AND credit > 0)
    )
);

CREATE INDEX idx_jel_account ON journal_entry_lines(account_id);
CREATE INDEX idx_jel_journal ON journal_entry_lines(journal_entry_id);
```

**Note on CockroachDB:** `gen_random_uuid()` is built in. `TIMESTAMPTZ` and `DECIMAL(19,4)` are standard. CHECK constraints are enforced. The `CK_JEL_XOR` constraint enforces the debit-XOR-credit rule at the database level — a second line of defense beyond service-layer validation.

### 4.5 Seed the chart of accounts

`src/main/resources/db/migration/V3__seed_chart_of_accounts.sql`:

```sql
-- Indian SME starter chart of accounts
INSERT INTO accounts (code, name, type, description) VALUES
-- ASSETS
('1001', 'Cash in Hand',           'ASSET',     'Petty cash on premises'),
('1002', 'Bank - Current Account', 'ASSET',     'Primary operational bank account'),
('1003', 'Bank - Savings Account', 'ASSET',     'Secondary bank account'),
('1100', 'Accounts Receivable',    'ASSET',     'Money owed by customers (control account)'),
('1200', 'GST Input Credit',       'ASSET',     'Input tax credit available'),
('1300', 'Prepaid Expenses',       'ASSET',     'Expenses paid in advance'),
('1400', 'Inventory',              'ASSET',     'Goods held for sale'),
('1500', 'Fixed Assets',           'ASSET',     'Property, plant, equipment'),
('1599', 'Accumulated Depreciation', 'ASSET',   'Contra-asset: cumulative depreciation'),

-- LIABILITIES
('2100', 'Accounts Payable',       'LIABILITY', 'Money owed to vendors (control account)'),
('2200', 'GST Output Payable',     'LIABILITY', 'Output tax collected'),
('2210', 'TDS Payable',            'LIABILITY', 'Tax deducted at source, payable to govt'),
('2300', 'Salaries Payable',       'LIABILITY', 'Wages owed to employees'),
('2400', 'Short-term Loans',       'LIABILITY', 'Loans repayable within 1 year'),
('2500', 'Long-term Loans',        'LIABILITY', 'Loans repayable beyond 1 year'),

-- EQUITY
('3000', 'Owner Capital',          'EQUITY',    'Capital contributed by owners'),
('3100', 'Retained Earnings',      'EQUITY',    'Accumulated profit/loss from prior years'),
('3200', 'Owner Drawings',         'EQUITY',    'Withdrawals by owner (contra-equity)'),

-- INCOME
('4000', 'Sales Revenue',          'INCOME',    'Revenue from goods sold'),
('4100', 'Service Revenue',        'INCOME',    'Revenue from services rendered'),
('4200', 'Interest Income',        'INCOME',    'Interest earned on deposits'),
('4900', 'Other Income',           'INCOME',    'Miscellaneous income'),

-- EXPENSES
('5000', 'Cost of Goods Sold',     'EXPENSE',   'Direct cost of products sold'),
('6000', 'Salaries & Wages',       'EXPENSE',   'Employee compensation'),
('6100', 'Rent Expense',           'EXPENSE',   'Office and premises rent'),
('6200', 'Utilities',              'EXPENSE',   'Electricity, water, internet'),
('6300', 'Office Supplies',        'EXPENSE',   'Stationery and consumables'),
('6400', 'Professional Fees',      'EXPENSE',   'Legal, accounting, consulting'),
('6500', 'Marketing & Advertising','EXPENSE',   'Promotion costs'),
('6600', 'Travel Expense',         'EXPENSE',   'Business travel costs'),
('6700', 'Depreciation Expense',   'EXPENSE',   'Period depreciation'),
('6800', 'Bank Charges',           'EXPENSE',   'Bank fees and commissions'),
('6900', 'Interest Expense',       'EXPENSE',   'Interest on loans'),
('6999', 'Miscellaneous Expense',  'EXPENSE',   'Other operating expenses');
```

### 4.6 Repositories

`ledger/repository/AccountRepository.java`:

```java
package com.company.app.finance.ledger.repository;

import com.company.app.finance.ledger.domain.Account;
import com.company.app.finance.ledger.domain.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByCode(String code);
    List<Account> findByType(AccountType type);
    List<Account> findByActiveTrue();
    boolean existsByCode(String code);
}
```

`ledger/repository/JournalEntryRepository.java`:

```java
package com.company.app.finance.ledger.repository;

import com.company.app.finance.ledger.domain.JournalEntry;
import com.company.app.finance.ledger.domain.JournalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByEntryNumber(String entryNumber);

    @EntityGraph(attributePaths = {"lines", "lines.account"})
    @Query("SELECT je FROM JournalEntry je WHERE je.id = :id")
    Optional<JournalEntry> findByIdWithLines(UUID id);

    Page<JournalEntry> findByEntryDateBetweenAndStatus(
        LocalDate from, LocalDate to, JournalStatus status, Pageable pageable);

    List<JournalEntry> findBySourceTypeAndSourceId(String sourceType, UUID sourceId);
}
```

### 4.7 DTOs

`ledger/dto/AccountDto.java`:

```java
package com.company.app.finance.ledger.dto;

import com.company.app.finance.ledger.domain.AccountType;
import java.util.UUID;

public record AccountDto(
    UUID id,
    String code,
    String name,
    AccountType type,
    UUID parentId,
    boolean active,
    String description
) {}
```

`ledger/dto/CreateAccountRequest.java`:

```java
package com.company.app.finance.ledger.dto;

import com.company.app.finance.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateAccountRequest(
    @NotBlank @Size(max = 20) String code,
    @NotBlank @Size(max = 200) String name,
    @NotNull AccountType type,
    UUID parentId,
    @Size(max = 500) String description
) {}
```

`ledger/dto/JournalEntryLineDto.java`:

```java
package com.company.app.finance.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalEntryLineDto(
    UUID id,
    UUID accountId,
    String accountCode,
    String accountName,
    BigDecimal debit,
    BigDecimal credit,
    String narration,
    int lineOrder
) {}
```

`ledger/dto/JournalEntryDto.java`:

```java
package com.company.app.finance.ledger.dto;

import com.company.app.finance.ledger.domain.JournalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalEntryDto(
    UUID id,
    String entryNumber,
    LocalDate entryDate,
    String description,
    String reference,
    JournalStatus status,
    UUID reversedByEntryId,
    UUID reversesEntryId,
    String sourceType,
    UUID sourceId,
    List<JournalEntryLineDto> lines,
    Instant createdAt,
    String createdBy
) {}
```

`ledger/dto/PostJournalRequest.java`:

```java
package com.company.app.finance.ledger.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PostJournalRequest(
    @NotNull LocalDate entryDate,
    @NotBlank @Size(max = 500) String description,
    @Size(max = 100) String reference,
    @NotNull @Size(min = 2, message = "At least 2 lines required") @Valid List<LineRequest> lines
) {
    public record LineRequest(
        @NotNull UUID accountId,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal debit,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal credit,
        @Size(max = 500) String narration
    ) {}
}
```

`ledger/dto/ReverseJournalRequest.java`:

```java
package com.company.app.finance.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReverseJournalRequest(
    @NotBlank @Size(max = 500) String reason
) {}
```

### 4.8 Mappers (MapStruct)

`ledger/mapper/AccountMapper.java`:

```java
package com.company.app.finance.ledger.mapper;

import com.company.app.finance.ledger.domain.Account;
import com.company.app.finance.ledger.dto.AccountDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    @Mapping(source = "parent.id", target = "parentId")
    AccountDto toDto(Account account);

    List<AccountDto> toDtos(List<Account> accounts);
}
```

`ledger/mapper/JournalEntryMapper.java`:

```java
package com.company.app.finance.ledger.mapper;

import com.company.app.finance.ledger.domain.JournalEntry;
import com.company.app.finance.ledger.domain.JournalEntryLine;
import com.company.app.finance.ledger.dto.JournalEntryDto;
import com.company.app.finance.ledger.dto.JournalEntryLineDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface JournalEntryMapper {

    @Mapping(target = "lines", source = "lines")
    JournalEntryDto toDto(JournalEntry entry);

    @Mapping(source = "account.id",   target = "accountId")
    @Mapping(source = "account.code", target = "accountCode")
    @Mapping(source = "account.name", target = "accountName")
    JournalEntryLineDto toLineDto(JournalEntryLine line);

    List<JournalEntryLineDto> toLineDtos(List<JournalEntryLine> lines);
}
```

### 4.9 Account service

`ledger/service/AccountService.java`:

```java
package com.company.app.finance.ledger.service;

import com.company.app.finance.ledger.domain.Account;
import com.company.app.finance.ledger.dto.AccountDto;
import com.company.app.finance.ledger.dto.CreateAccountRequest;
import com.company.app.finance.ledger.mapper.AccountMapper;
import com.company.app.finance.ledger.repository.AccountRepository;
import com.company.app.finance.shared.exception.AccountNotFoundException;
import com.company.app.finance.shared.exception.InvalidJournalException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository repo;
    private final AccountMapper mapper;

    @Transactional(readOnly = true)
    public List<AccountDto> findAll() {
        return mapper.toDtos(repo.findAll());
    }

    @Transactional(readOnly = true)
    public List<AccountDto> findAllActive() {
        return mapper.toDtos(repo.findByActiveTrue());
    }

    @Transactional(readOnly = true)
    public AccountDto findById(UUID id) {
        return mapper.toDto(repo.findById(id)
            .orElseThrow(() -> new AccountNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public AccountDto findByCode(String code) {
        return mapper.toDto(repo.findByCode(code)
            .orElseThrow(() -> new AccountNotFoundException(code)));
    }

    @Transactional
    public AccountDto create(CreateAccountRequest req) {
        if (repo.existsByCode(req.code())) {
            throw new InvalidJournalException("Account code already exists: " + req.code());
        }
        Account a = new Account();
        a.setCode(req.code());
        a.setName(req.name());
        a.setType(req.type());
        a.setDescription(req.description());
        a.setActive(true);
        if (req.parentId() != null) {
            Account parent = repo.findById(req.parentId())
                .orElseThrow(() -> new AccountNotFoundException(req.parentId()));
            if (parent.getType() != req.type()) {
                throw new InvalidJournalException(
                    "Child account type must match parent type");
            }
            a.setParent(parent);
        }
        return mapper.toDto(repo.save(a));
    }

    @Transactional
    public AccountDto deactivate(UUID id) {
        Account a = repo.findById(id)
            .orElseThrow(() -> new AccountNotFoundException(id));
        a.setActive(false);
        return mapper.toDto(a);
    }
}
```

### 4.10 The posting service — the most important class

This is **the** piece. It must be bulletproof. Read every line.

`ledger/service/JournalPostingService.java`:

```java
package com.company.app.finance.ledger.service;

import com.company.app.finance.ledger.domain.Account;
import com.company.app.finance.ledger.domain.JournalEntry;
import com.company.app.finance.ledger.domain.JournalEntryLine;
import com.company.app.finance.ledger.domain.JournalStatus;
import com.company.app.finance.ledger.dto.JournalEntryDto;
import com.company.app.finance.ledger.dto.PostJournalRequest;
import com.company.app.finance.ledger.dto.ReverseJournalRequest;
import com.company.app.finance.ledger.mapper.JournalEntryMapper;
import com.company.app.finance.ledger.repository.AccountRepository;
import com.company.app.finance.ledger.repository.JournalEntryRepository;
import com.company.app.finance.shared.exception.AccountNotFoundException;
import com.company.app.finance.shared.exception.InvalidJournalException;
import com.company.app.finance.shared.exception.UnbalancedEntryException;
import com.company.app.finance.shared.money.MoneyUtils;
import com.company.app.finance.shared.numbering.EntryNumberGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JournalPostingService {

    private final JournalEntryRepository journalRepo;
    private final AccountRepository accountRepo;
    private final EntryNumberGenerator numberGen;
    private final JournalEntryMapper mapper;

    // ============================================================
    // Public API
    // ============================================================

    @Transactional
    public JournalEntryDto post(PostJournalRequest req) {
        return mapper.toDto(postInternal(req, "MANUAL", null));
    }

    @Transactional
    public JournalEntryDto postSystem(PostJournalRequest req, String sourceType, UUID sourceId) {
        return mapper.toDto(postInternal(req, sourceType, sourceId));
    }

    @Transactional
    public JournalEntry postInternal(PostJournalRequest req, String sourceType, UUID sourceId) {
        validateRequest(req);

        JournalEntry entry = new JournalEntry();
        entry.setEntryNumber(numberGen.next("JE"));
        entry.setEntryDate(req.entryDate());
        entry.setDescription(req.description());
        entry.setReference(req.reference());
        entry.setStatus(JournalStatus.POSTED);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);

        // Pre-fetch all accounts in one query to avoid N+1
        List<UUID> accountIds = req.lines().stream()
            .map(PostJournalRequest.LineRequest::accountId).distinct().toList();
        Map<UUID, Account> accountMap = new HashMap<>();
        for (Account a : accountRepo.findAllById(accountIds)) {
            accountMap.put(a.getId(), a);
        }
        for (UUID id : accountIds) {
            if (!accountMap.containsKey(id)) {
                throw new AccountNotFoundException(id);
            }
            if (!accountMap.get(id).isActive()) {
                throw new InvalidJournalException(
                    "Account is inactive: " + accountMap.get(id).getCode());
            }
        }

        int order = 0;
        for (var lineReq : req.lines()) {
            JournalEntryLine line = new JournalEntryLine();
            line.setAccount(accountMap.get(lineReq.accountId()));
            line.setDebit(MoneyUtils.normalize(lineReq.debit()));
            line.setCredit(MoneyUtils.normalize(lineReq.credit()));
            line.setNarration(lineReq.narration());
            line.setLineOrder(order++);
            entry.addLine(line);
        }

        JournalEntry saved = journalRepo.save(entry);
        log.info("Posted journal entry {} on {} (source={}/{})",
            saved.getEntryNumber(), saved.getEntryDate(), sourceType, sourceId);
        return saved;
    }

    @Transactional
    public JournalEntryDto reverse(UUID entryId, ReverseJournalRequest req) {
        JournalEntry original = journalRepo.findByIdWithLines(entryId)
            .orElseThrow(() -> new InvalidJournalException("Journal entry not found: " + entryId));

        if (original.getStatus() != JournalStatus.POSTED) {
            throw new InvalidJournalException(
                "Only POSTED entries can be reversed; current status: " + original.getStatus());
        }
        if (original.getReversedByEntryId() != null) {
            throw new InvalidJournalException("Entry already reversed");
        }

        // Build reversal lines: swap debit and credit on each line
        List<PostJournalRequest.LineRequest> reversedLines = original.getLines().stream()
            .map(l -> new PostJournalRequest.LineRequest(
                l.getAccount().getId(),
                l.getCredit(),   // swap
                l.getDebit(),    // swap
                "Reversal: " + (l.getNarration() == null ? "" : l.getNarration())))
            .toList();

        PostJournalRequest reversalReq = new PostJournalRequest(
            LocalDate.now(),
            "Reversal of " + original.getEntryNumber() + " - " + req.reason(),
            original.getEntryNumber(),
            reversedLines
        );

        JournalEntry reversal = postInternal(reversalReq, "REVERSAL", original.getId());
        reversal.setReversesEntryId(original.getId());

        original.setStatus(JournalStatus.REVERSED);
        original.setReversedByEntryId(reversal.getId());

        log.info("Reversed entry {} via {}", original.getEntryNumber(), reversal.getEntryNumber());
        return mapper.toDto(reversal);
    }

    @Transactional(readOnly = true)
    public JournalEntryDto findById(UUID id) {
        JournalEntry entry = journalRepo.findByIdWithLines(id)
            .orElseThrow(() -> new InvalidJournalException("Journal entry not found: " + id));
        return mapper.toDto(entry);
    }

    // ============================================================
    // Validation — the invariants that must hold
    // ============================================================

    private void validateRequest(PostJournalRequest req) {
        if (req.lines() == null || req.lines().size() < 2) {
            throw new InvalidJournalException("At least 2 lines required");
        }
        if (req.entryDate().isAfter(LocalDate.now())) {
            throw new InvalidJournalException("Entry date cannot be in the future");
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (int i = 0; i < req.lines().size(); i++) {
            var line = req.lines().get(i);
            BigDecimal dr = MoneyUtils.normalize(line.debit());
            BigDecimal cr = MoneyUtils.normalize(line.credit());

            boolean hasDebit = MoneyUtils.isPositive(dr);
            boolean hasCredit = MoneyUtils.isPositive(cr);

            if (MoneyUtils.isNegative(dr) || MoneyUtils.isNegative(cr)) {
                throw new InvalidJournalException(
                    "Line " + (i + 1) + ": amounts must be non-negative");
            }
            if (hasDebit && hasCredit) {
                throw new InvalidJournalException(
                    "Line " + (i + 1) + ": cannot have both debit and credit");
            }
            if (!hasDebit && !hasCredit) {
                throw new InvalidJournalException(
                    "Line " + (i + 1) + ": must have either debit or credit");
            }

            totalDebit  = totalDebit.add(dr);
            totalCredit = totalCredit.add(cr);
        }

        totalDebit  = MoneyUtils.normalize(totalDebit);
        totalCredit = MoneyUtils.normalize(totalCredit);

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new UnbalancedEntryException(totalDebit, totalCredit);
        }
        if (MoneyUtils.isZero(totalDebit)) {
            throw new InvalidJournalException("Journal entry total cannot be zero");
        }
    }
}
```

**Things worth highlighting in this service:**

- `validateRequest` runs **before** any DB writes. If it throws, nothing is persisted.
- `BigDecimal.compareTo` is used for equality, never `equals` — because `equals` considers scale (1.00 ≠ 1.000) and `compareTo` doesn't.
- All amounts go through `MoneyUtils.normalize` → consistent scale.
- Accounts fetched in **one** query via `findAllById`, not N queries inside the loop.
- `postSystem` is used by downstream services (invoice, payment) to tag the source. `post` is for human-driven manual entries.
- Reversal is a brand-new POSTED entry that points back to the original via `reversesEntryId`, and the original is marked REVERSED via `reversedByEntryId`. The audit trail is complete in both directions.

### 4.11 Controllers

`ledger/web/AccountController.java`:

```java
package com.company.app.finance.ledger.web;

import com.company.app.finance.ledger.dto.AccountDto;
import com.company.app.finance.ledger.dto.CreateAccountRequest;
import com.company.app.finance.ledger.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public List<AccountDto> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? service.findAllActive() : service.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public AccountDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping("/by-code/{code}")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public AccountDto getByCode(@PathVariable String code) {
        return service.findByCode(code);
    }

    @PostMapping
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ResponseEntity<AccountDto> create(@Valid @RequestBody CreateAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public AccountDto deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }
}
```

`ledger/web/JournalEntryController.java`:

```java
package com.company.app.finance.ledger.web;

import com.company.app.finance.ledger.dto.JournalEntryDto;
import com.company.app.finance.ledger.dto.PostJournalRequest;
import com.company.app.finance.ledger.dto.ReverseJournalRequest;
import com.company.app.finance.ledger.service.JournalPostingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/finance/journal-entries")
@RequiredArgsConstructor
public class JournalEntryController {

    private final JournalPostingService service;

    @PostMapping
    @PreAuthorize("hasRole('FINANCE_APPROVER')")
    public ResponseEntity<JournalEntryDto> post(@Valid @RequestBody PostJournalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.post(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public JournalEntryDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ResponseEntity<JournalEntryDto> reverse(
            @PathVariable UUID id,
            @Valid @RequestBody ReverseJournalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reverse(id, req));
    }
}
```

### 4.12 What you can now do

At the end of Phase 1 you have a working ledger:

```bash
# Post a manual entry: owner contributes ₹100,000 capital to bank
curl -u approver:approver -X POST http://localhost:8080/api/finance/journal-entries \
  -H "Content-Type: application/json" \
  -d '{
    "entryDate": "2026-05-27",
    "description": "Initial capital contribution",
    "reference": null,
    "lines": [
      {"accountId": "<bank-account-uuid>",    "debit": 100000.00, "credit": 0, "narration": "Bank deposit"},
      {"accountId": "<capital-account-uuid>", "debit": 0,         "credit": 100000.00, "narration": "Owner capital"}
    ]
  }'
```

Try posting an unbalanced entry (debit ≠ credit) — should return 422 with `JE_UNBALANCED`. That single test confirms the invariant is enforced.

---


## 5. Phase 2 — Customer Master

Customers are simple. Build them right so they don't bite you later.

### 5.1 Embeddable Address

`master/customer/domain/Address.java`:

```java
package com.company.app.finance.master.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter @Setter
@NoArgsConstructor
public class Address {
    @Column(name = "address_line1", length = 200) private String line1;
    @Column(name = "address_line2", length = 200) private String line2;
    @Column(name = "address_city",  length = 100) private String city;
    @Column(name = "address_state", length = 100) private String state;
    @Column(name = "address_state_code", length = 2) private String stateCode;
    @Column(name = "address_postal_code", length = 20) private String postalCode;
    @Column(name = "address_country", length = 2) private String country;
}
```

### 5.2 Customer entity

`master/customer/domain/Customer.java`:

```java
package com.company.app.finance.master.customer.domain;

import com.company.app.finance.shared.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "customers",
    uniqueConstraints = @UniqueConstraint(name = "uk_customer_code", columnNames = "code"),
    indexes = {
        @Index(name = "idx_customer_name", columnList = "name"),
        @Index(name = "idx_customer_gstin", columnList = "gstin")
    })
@Getter @Setter
@NoArgsConstructor
public class Customer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 15)
    @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
             message = "Invalid GSTIN format")
    private String gstin;

    @Column(length = 10)
    private String pan;

    @Column(length = 200)
    private String email;

    @Column(length = 20)
    private String phone;

    @Embedded
    private Address billingAddress = new Address();

    @Column(name = "credit_limit", precision = 19, scale = 4)
    private java.math.BigDecimal creditLimit;

    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays = 30;

    @Column(nullable = false)
    private boolean active = true;
}
```

### 5.3 Migration

`src/main/resources/db/migration/V4__customer.sql`:

```sql
CREATE TABLE customers (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    code                  VARCHAR(30)  NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    gstin                 VARCHAR(15),
    pan                   VARCHAR(10),
    email                 VARCHAR(200),
    phone                 VARCHAR(20),
    address_line1         VARCHAR(200),
    address_line2         VARCHAR(200),
    address_city          VARCHAR(100),
    address_state         VARCHAR(100),
    address_state_code    VARCHAR(2),
    address_postal_code   VARCHAR(20),
    address_country       VARCHAR(2),
    credit_limit          DECIMAL(19,4),
    payment_terms_days    INT          DEFAULT 30,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_customer_code UNIQUE (code)
);

CREATE INDEX idx_customer_name  ON customers(name);
CREATE INDEX idx_customer_gstin ON customers(gstin);
```

### 5.4 Repository, DTOs, mapper, service, controller

`master/customer/repository/CustomerRepository.java`:

```java
package com.company.app.finance.master.customer.repository;

import com.company.app.finance.master.customer.domain.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByCode(String code);
    boolean existsByCode(String code);
    Page<Customer> findByNameContainingIgnoreCaseAndActive(String name, boolean active, Pageable pageable);
}
```

`master/customer/dto/AddressDto.java`:

```java
package com.company.app.finance.master.customer.dto;

public record AddressDto(
    String line1, String line2, String city, String state,
    String stateCode, String postalCode, String country
) {}
```

`master/customer/dto/CustomerDto.java`:

```java
package com.company.app.finance.master.customer.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CustomerDto(
    UUID id, String code, String name, String gstin, String pan,
    String email, String phone, AddressDto billingAddress,
    BigDecimal creditLimit, Integer paymentTermsDays, boolean active
) {}
```

`master/customer/dto/CreateCustomerRequest.java`:

```java
package com.company.app.finance.master.customer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateCustomerRequest(
    @NotBlank @Size(max = 30) String code,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 15) String gstin,
    @Size(max = 10) String pan,
    @Email @Size(max = 200) String email,
    @Size(max = 20) String phone,
    @Valid AddressDto billingAddress,
    @DecimalMin("0.0") BigDecimal creditLimit,
    @Min(0) @Max(365) Integer paymentTermsDays
) {}
```

`master/customer/dto/UpdateCustomerRequest.java`:

```java
package com.company.app.finance.master.customer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateCustomerRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 15) String gstin,
    @Size(max = 10) String pan,
    @Email @Size(max = 200) String email,
    @Size(max = 20) String phone,
    @Valid AddressDto billingAddress,
    @DecimalMin("0.0") BigDecimal creditLimit,
    @Min(0) @Max(365) Integer paymentTermsDays
) {}
```

`master/customer/mapper/CustomerMapper.java`:

```java
package com.company.app.finance.master.customer.mapper;

import com.company.app.finance.master.customer.domain.Address;
import com.company.app.finance.master.customer.domain.Customer;
import com.company.app.finance.master.customer.dto.AddressDto;
import com.company.app.finance.master.customer.dto.CustomerDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CustomerMapper {
    CustomerDto toDto(Customer customer);
    AddressDto toAddressDto(Address address);
    Address toAddress(AddressDto dto);
}
```

`master/customer/service/CustomerService.java`:

```java
package com.company.app.finance.master.customer.service;

import com.company.app.finance.master.customer.domain.Customer;
import com.company.app.finance.master.customer.dto.*;
import com.company.app.finance.master.customer.mapper.CustomerMapper;
import com.company.app.finance.master.customer.repository.CustomerRepository;
import com.company.app.finance.shared.exception.InvalidJournalException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository repo;
    private final CustomerMapper mapper;

    @Transactional(readOnly = true)
    public Page<CustomerDto> search(String name, Pageable pageable) {
        return repo.findByNameContainingIgnoreCaseAndActive(
                name == null ? "" : name, true, pageable)
            .map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public CustomerDto findById(UUID id) {
        return mapper.toDto(getEntity(id));
    }

    @Transactional
    public CustomerDto create(CreateCustomerRequest req) {
        if (repo.existsByCode(req.code())) {
            throw new InvalidJournalException("Customer code already exists: " + req.code());
        }
        Customer c = new Customer();
        c.setCode(req.code());
        c.setName(req.name());
        c.setGstin(req.gstin());
        c.setPan(req.pan());
        c.setEmail(req.email());
        c.setPhone(req.phone());
        if (req.billingAddress() != null) c.setBillingAddress(mapper.toAddress(req.billingAddress()));
        c.setCreditLimit(req.creditLimit());
        c.setPaymentTermsDays(req.paymentTermsDays() == null ? 30 : req.paymentTermsDays());
        c.setActive(true);
        return mapper.toDto(repo.save(c));
    }

    @Transactional
    public CustomerDto update(UUID id, UpdateCustomerRequest req) {
        Customer c = getEntity(id);
        c.setName(req.name());
        c.setGstin(req.gstin());
        c.setPan(req.pan());
        c.setEmail(req.email());
        c.setPhone(req.phone());
        if (req.billingAddress() != null) c.setBillingAddress(mapper.toAddress(req.billingAddress()));
        c.setCreditLimit(req.creditLimit());
        if (req.paymentTermsDays() != null) c.setPaymentTermsDays(req.paymentTermsDays());
        return mapper.toDto(c);
    }

    @Transactional
    public void deactivate(UUID id) {
        getEntity(id).setActive(false);
    }

    Customer getEntity(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Customer not found: " + id));
    }
}
```

`master/customer/web/CustomerController.java`:

```java
package com.company.app.finance.master.customer.web;

import com.company.app.finance.master.customer.dto.*;
import com.company.app.finance.master.customer.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/finance/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public Page<CustomerDto> list(@RequestParam(required = false) String name, Pageable pageable) {
        return service.search(name, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public CustomerDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public ResponseEntity<CustomerDto> create(@Valid @RequestBody CreateCustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public CustomerDto update(@PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
```

---

## 6. Phase 3 — Invoices

This is where the ledger gets real work. **Issuing an invoice posts a journal entry**: DR Accounts Receivable, CR Revenue. That's how the document layer feeds the ledger.

### 6.1 Enums

`invoice/domain/InvoiceStatus.java`:

```java
package com.company.app.finance.invoice.domain;

public enum InvoiceStatus {
    DRAFT,
    ISSUED,           // posted to ledger, awaiting payment
    PARTIALLY_PAID,
    PAID,
    CANCELLED         // void; if ISSUED, requires JE reversal
}
```

### 6.2 Entities

`invoice/domain/Invoice.java`:

```java
package com.company.app.finance.invoice.domain;

import com.company.app.finance.master.customer.domain.Customer;
import com.company.app.finance.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices",
    uniqueConstraints = @UniqueConstraint(name = "uk_invoice_number", columnNames = "invoice_number"),
    indexes = {
        @Index(name = "idx_invoice_customer", columnList = "customer_id"),
        @Index(name = "idx_invoice_status", columnList = "status"),
        @Index(name = "idx_invoice_date", columnList = "invoice_date")
    })
@Getter @Setter
@NoArgsConstructor
public class Invoice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, length = 30)
    private String invoiceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal subtotal  = BigDecimal.ZERO;

    @Column(name = "tax_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal taxTotal  = BigDecimal.ZERO;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal total     = BigDecimal.ZERO;

    @Column(name = "amount_paid", precision = 19, scale = 4, nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;             // JE created on issue

    @Column(name = "reversal_je_id")
    private UUID reversalJournalEntryId;     // JE created on cancel (after issue)

    @Column(length = 500)
    private String notes;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true,
        fetch = FetchType.LAZY)
    @OrderColumn(name = "line_order")
    private List<InvoiceLine> lines = new ArrayList<>();

    public void addLine(InvoiceLine line) {
        line.setInvoice(this);
        this.lines.add(line);
    }

    public BigDecimal balanceDue() {
        return total.subtract(amountPaid);
    }
}
```

`invoice/domain/InvoiceLine.java`:

```java
package com.company.app.finance.invoice.domain;

import com.company.app.finance.ledger.domain.Account;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_lines",
    indexes = @Index(name = "idx_invl_invoice", columnList = "invoice_id"))
@Getter @Setter
@NoArgsConstructor
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "line_subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineSubtotal;     // quantity * unitPrice

    @Column(name = "tax_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal taxRate = BigDecimal.ZERO;    // e.g. 18.0000 for 18% GST

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;         // lineSubtotal + taxAmount

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revenue_account_id", nullable = false)
    private Account revenueAccount;       // which INCOME account to credit
}
```

### 6.3 Migration

`src/main/resources/db/migration/V5__invoice.sql`:

```sql
CREATE TABLE invoices (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    invoice_number        VARCHAR(30)  NOT NULL,
    customer_id           UUID         NOT NULL,
    invoice_date          DATE         NOT NULL,
    due_date              DATE         NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    subtotal              DECIMAL(19,4) NOT NULL DEFAULT 0,
    tax_total             DECIMAL(19,4) NOT NULL DEFAULT 0,
    total                 DECIMAL(19,4) NOT NULL DEFAULT 0,
    amount_paid           DECIMAL(19,4) NOT NULL DEFAULT 0,
    journal_entry_id      UUID,
    reversal_je_id        UUID,
    notes                 VARCHAR(500),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_invoice_number UNIQUE (invoice_number),
    CONSTRAINT fk_invoice_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_invoice_je       FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id),
    CONSTRAINT fk_invoice_rev_je   FOREIGN KEY (reversal_je_id)   REFERENCES journal_entries(id),
    CONSTRAINT ck_invoice_status   CHECK (status IN ('DRAFT','ISSUED','PARTIALLY_PAID','PAID','CANCELLED')),
    CONSTRAINT ck_invoice_amounts  CHECK (subtotal >= 0 AND tax_total >= 0 AND total >= 0 AND amount_paid >= 0)
);

CREATE INDEX idx_invoice_customer ON invoices(customer_id);
CREATE INDEX idx_invoice_status   ON invoices(status);
CREATE INDEX idx_invoice_date     ON invoices(invoice_date);

CREATE TABLE invoice_lines (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    invoice_id          UUID          NOT NULL,
    line_order          INT           NOT NULL DEFAULT 0,
    description         VARCHAR(500)  NOT NULL,
    quantity            DECIMAL(19,4) NOT NULL,
    unit_price          DECIMAL(19,4) NOT NULL,
    line_subtotal       DECIMAL(19,4) NOT NULL,
    tax_rate            DECIMAL(7,4)  NOT NULL DEFAULT 0,
    tax_amount          DECIMAL(19,4) NOT NULL DEFAULT 0,
    line_total          DECIMAL(19,4) NOT NULL,
    revenue_account_id  UUID          NOT NULL,

    CONSTRAINT fk_invl_invoice FOREIGN KEY (invoice_id)         REFERENCES invoices(id),
    CONSTRAINT fk_invl_account FOREIGN KEY (revenue_account_id) REFERENCES accounts(id),
    CONSTRAINT ck_invl_qty     CHECK (quantity   > 0),
    CONSTRAINT ck_invl_price   CHECK (unit_price >= 0)
);

CREATE INDEX idx_invl_invoice ON invoice_lines(invoice_id);
```

### 6.4 DTOs

`invoice/dto/CreateInvoiceRequest.java`:

```java
package com.company.app.finance.invoice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateInvoiceRequest(
    @NotNull UUID customerId,
    @NotNull LocalDate invoiceDate,
    @NotNull LocalDate dueDate,
    @Size(max = 500) String notes,
    @NotNull @Size(min = 1) @Valid List<LineRequest> lines
) {
    public record LineRequest(
        @NotBlank @Size(max = 500) String description,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0") BigDecimal unitPrice,
        @NotNull @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal taxRate,
        @NotNull UUID revenueAccountId
    ) {}
}
```

`invoice/dto/InvoiceLineDto.java`:

```java
package com.company.app.finance.invoice.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceLineDto(
    UUID id, String description, BigDecimal quantity, BigDecimal unitPrice,
    BigDecimal lineSubtotal, BigDecimal taxRate, BigDecimal taxAmount, BigDecimal lineTotal,
    UUID revenueAccountId, String revenueAccountCode
) {}
```

`invoice/dto/InvoiceDto.java`:

```java
package com.company.app.finance.invoice.dto;

import com.company.app.finance.invoice.domain.InvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceDto(
    UUID id, String invoiceNumber,
    UUID customerId, String customerName,
    LocalDate invoiceDate, LocalDate dueDate,
    InvoiceStatus status,
    BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total,
    BigDecimal amountPaid, BigDecimal balanceDue,
    UUID journalEntryId, UUID reversalJournalEntryId,
    String notes,
    List<InvoiceLineDto> lines
) {}
```

### 6.5 Mapper

`invoice/mapper/InvoiceMapper.java`:

```java
package com.company.app.finance.invoice.mapper;

import com.company.app.finance.invoice.domain.Invoice;
import com.company.app.finance.invoice.domain.InvoiceLine;
import com.company.app.finance.invoice.dto.InvoiceDto;
import com.company.app.finance.invoice.dto.InvoiceLineDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.math.BigDecimal;

@Mapper(componentModel = "spring")
public interface InvoiceMapper {

    @Mapping(source = "customer.id",   target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    @Mapping(target = "balanceDue", expression = "java(invoice.balanceDue())")
    InvoiceDto toDto(Invoice invoice);

    @Mapping(source = "revenueAccount.id",   target = "revenueAccountId")
    @Mapping(source = "revenueAccount.code", target = "revenueAccountCode")
    InvoiceLineDto toLineDto(InvoiceLine line);
}
```

### 6.6 Invoice repository

`invoice/repository/InvoiceRepository.java`:

```java
package com.company.app.finance.invoice.repository;

import com.company.app.finance.invoice.domain.Invoice;
import com.company.app.finance.invoice.domain.InvoiceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @EntityGraph(attributePaths = {"lines", "lines.revenueAccount", "customer"})
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> findByIdWithLines(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);

    Page<Invoice> findByCustomerId(UUID customerId, Pageable pageable);
    Page<Invoice> findByStatus(InvoiceStatus status, Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.customer.id = :customerId " +
           "AND i.status IN ('ISSUED','PARTIALLY_PAID') ORDER BY i.invoiceDate ASC")
    List<Invoice> findOpenInvoicesByCustomer(@Param("customerId") UUID customerId);
}
```

### 6.7 Invoice service — the meat

`invoice/service/InvoiceService.java`:

```java
package com.company.app.finance.invoice.service;

import com.company.app.finance.invoice.domain.Invoice;
import com.company.app.finance.invoice.domain.InvoiceLine;
import com.company.app.finance.invoice.domain.InvoiceStatus;
import com.company.app.finance.invoice.dto.CreateInvoiceRequest;
import com.company.app.finance.invoice.dto.InvoiceDto;
import com.company.app.finance.invoice.mapper.InvoiceMapper;
import com.company.app.finance.invoice.repository.InvoiceRepository;
import com.company.app.finance.ledger.domain.Account;
import com.company.app.finance.ledger.domain.AccountType;
import com.company.app.finance.ledger.domain.JournalEntry;
import com.company.app.finance.ledger.dto.PostJournalRequest;
import com.company.app.finance.ledger.dto.PostJournalRequest.LineRequest;
import com.company.app.finance.ledger.dto.ReverseJournalRequest;
import com.company.app.finance.ledger.repository.AccountRepository;
import com.company.app.finance.ledger.repository.JournalEntryRepository;
import com.company.app.finance.ledger.service.JournalPostingService;
import com.company.app.finance.master.customer.domain.Customer;
import com.company.app.finance.master.customer.repository.CustomerRepository;
import com.company.app.finance.shared.exception.AccountNotFoundException;
import com.company.app.finance.shared.exception.InvalidJournalException;
import com.company.app.finance.shared.money.MoneyUtils;
import com.company.app.finance.shared.numbering.EntryNumberGenerator;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepo;
    private final CustomerRepository customerRepo;
    private final AccountRepository accountRepo;
    private final JournalEntryRepository journalRepo;
    private final JournalPostingService postingService;
    private final EntryNumberGenerator numberGen;
    private final InvoiceMapper mapper;

    // Hardcoded control-account codes. In v2, make these configurable per-company.
    private static final String AR_ACCOUNT_CODE  = "1100";   // Accounts Receivable
    private static final String GST_OUT_CODE     = "2200";   // GST Output Payable

    @Transactional(readOnly = true)
    public InvoiceDto findById(UUID id) {
        return mapper.toDto(invoiceRepo.findByIdWithLines(id)
            .orElseThrow(() -> new EntityNotFoundException("Invoice not found: " + id)));
    }

    @Transactional
    public InvoiceDto createDraft(CreateInvoiceRequest req) {
        if (req.dueDate().isBefore(req.invoiceDate())) {
            throw new InvalidJournalException("Due date cannot be before invoice date");
        }

        Customer customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> new EntityNotFoundException("Customer not found: " + req.customerId()));
        if (!customer.isActive()) {
            throw new InvalidJournalException("Customer is inactive: " + customer.getCode());
        }

        // Pre-fetch revenue accounts
        List<UUID> accountIds = req.lines().stream().map(l -> l.revenueAccountId()).distinct().toList();
        Map<UUID, Account> accountMap = new HashMap<>();
        for (Account a : accountRepo.findAllById(accountIds)) accountMap.put(a.getId(), a);

        Invoice inv = new Invoice();
        inv.setInvoiceNumber(numberGen.next("INV"));
        inv.setCustomer(customer);
        inv.setInvoiceDate(req.invoiceDate());
        inv.setDueDate(req.dueDate());
        inv.setStatus(InvoiceStatus.DRAFT);
        inv.setNotes(req.notes());

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;

        for (var lr : req.lines()) {
            Account acc = accountMap.get(lr.revenueAccountId());
            if (acc == null) throw new AccountNotFoundException(lr.revenueAccountId());
            if (acc.getType() != AccountType.INCOME) {
                throw new InvalidJournalException(
                    "Revenue account must be of type INCOME: " + acc.getCode());
            }
            if (!acc.isActive()) {
                throw new InvalidJournalException("Account is inactive: " + acc.getCode());
            }

            BigDecimal qty   = MoneyUtils.normalize(lr.quantity());
            BigDecimal price = MoneyUtils.normalize(lr.unitPrice());
            BigDecimal lineSub = MoneyUtils.normalize(qty.multiply(price));
            BigDecimal rate  = MoneyUtils.normalize(lr.taxRate());
            BigDecimal taxAmt = MoneyUtils.normalize(
                lineSub.multiply(rate).divide(BigDecimal.valueOf(100)));
            BigDecimal lineTotal = MoneyUtils.normalize(lineSub.add(taxAmt));

            InvoiceLine line = new InvoiceLine();
            line.setDescription(lr.description());
            line.setQuantity(qty);
            line.setUnitPrice(price);
            line.setLineSubtotal(lineSub);
            line.setTaxRate(rate);
            line.setTaxAmount(taxAmt);
            line.setLineTotal(lineTotal);
            line.setRevenueAccount(acc);
            inv.addLine(line);

            subtotal = subtotal.add(lineSub);
            taxTotal = taxTotal.add(taxAmt);
        }

        inv.setSubtotal(MoneyUtils.normalize(subtotal));
        inv.setTaxTotal(MoneyUtils.normalize(taxTotal));
        inv.setTotal(MoneyUtils.normalize(subtotal.add(taxTotal)));

        Invoice saved = invoiceRepo.save(inv);
        log.info("Created draft invoice {}", saved.getInvoiceNumber());
        return mapper.toDto(saved);
    }

    /**
     * Posts the journal entry, transitions DRAFT → ISSUED.
     * JE layout:
     *   DR Accounts Receivable      (total)
     *     CR Revenue account(s)     (per line, grouped)
     *     CR GST Output Payable     (if any tax)
     */
    @Transactional
    public InvoiceDto issue(UUID invoiceId) {
        Invoice inv = invoiceRepo.findByIdWithLines(invoiceId)
            .orElseThrow(() -> new EntityNotFoundException("Invoice not found: " + invoiceId));
        if (inv.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvalidJournalException(
                "Only DRAFT invoices can be issued. Current: " + inv.getStatus());
        }

        Account ar = accountRepo.findByCode(AR_ACCOUNT_CODE)
            .orElseThrow(() -> new AccountNotFoundException(AR_ACCOUNT_CODE));

        List<LineRequest> jeLines = new ArrayList<>();

        // DR Accounts Receivable for total
        jeLines.add(new LineRequest(ar.getId(), inv.getTotal(), BigDecimal.ZERO,
            "Receivable - " + inv.getInvoiceNumber()));

        // CR each revenue account, grouped by account
        Map<UUID, BigDecimal> revenueGrouped = new LinkedHashMap<>();
        for (InvoiceLine line : inv.getLines()) {
            revenueGrouped.merge(line.getRevenueAccount().getId(),
                line.getLineSubtotal(), BigDecimal::add);
        }
        for (var entry : revenueGrouped.entrySet()) {
            jeLines.add(new LineRequest(entry.getKey(),
                BigDecimal.ZERO, MoneyUtils.normalize(entry.getValue()),
                "Revenue - " + inv.getInvoiceNumber()));
        }

        // CR GST Output Payable (if applicable)
        if (MoneyUtils.isPositive(inv.getTaxTotal())) {
            Account gstOut = accountRepo.findByCode(GST_OUT_CODE)
                .orElseThrow(() -> new AccountNotFoundException(GST_OUT_CODE));
            jeLines.add(new LineRequest(gstOut.getId(), BigDecimal.ZERO, inv.getTaxTotal(),
                "GST output - " + inv.getInvoiceNumber()));
        }

        PostJournalRequest jeReq = new PostJournalRequest(
            inv.getInvoiceDate(),
            "Invoice " + inv.getInvoiceNumber() + " to " + inv.getCustomer().getName(),
            inv.getInvoiceNumber(),
            jeLines
        );

        JournalEntry je = postingService.postInternal(jeReq, "INVOICE", inv.getId());
        inv.setJournalEntryId(je.getId());
        inv.setStatus(InvoiceStatus.ISSUED);

        log.info("Issued invoice {} (JE: {})", inv.getInvoiceNumber(), je.getEntryNumber());
        return mapper.toDto(inv);
    }

    /**
     * Cancel an invoice.
     * - DRAFT: just transition to CANCELLED
     * - ISSUED with no payments: reverse the JE, transition to CANCELLED
     * - Anything paid: refuse — issue a credit note in v2 instead
     */
    @Transactional
    public InvoiceDto cancel(UUID invoiceId, String reason) {
        Invoice inv = invoiceRepo.findByIdWithLines(invoiceId)
            .orElseThrow(() -> new EntityNotFoundException("Invoice not found: " + invoiceId));

        switch (inv.getStatus()) {
            case DRAFT -> inv.setStatus(InvoiceStatus.CANCELLED);
            case ISSUED -> {
                if (MoneyUtils.isPositive(inv.getAmountPaid())) {
                    throw new InvalidJournalException(
                        "Cannot cancel a paid invoice; issue a credit note instead");
                }
                JournalEntry reversal = journalRepo.findByIdWithLines(inv.getJournalEntryId())
                    .map(je -> postingService.reverse(je.getId(),
                        new ReverseJournalRequest(reason)).id())
                    .map(id -> journalRepo.findById(id).orElseThrow())
                    .orElseThrow(() -> new InvalidJournalException("Original JE not found"));
                inv.setReversalJournalEntryId(reversal.getId());
                inv.setStatus(InvoiceStatus.CANCELLED);
            }
            case PARTIALLY_PAID, PAID -> throw new InvalidJournalException(
                "Cannot cancel a paid invoice; issue a credit note instead");
            case CANCELLED -> throw new InvalidJournalException("Invoice already cancelled");
        }

        log.info("Cancelled invoice {}: {}", inv.getInvoiceNumber(), reason);
        return mapper.toDto(inv);
    }
}
```

### 6.8 Controller

`invoice/web/InvoiceController.java`:

```java
package com.company.app.finance.invoice.web;

import com.company.app.finance.invoice.dto.CreateInvoiceRequest;
import com.company.app.finance.invoice.dto.InvoiceDto;
import com.company.app.finance.invoice.service.InvoiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/finance/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService service;

    @PostMapping
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public ResponseEntity<InvoiceDto> createDraft(@Valid @RequestBody CreateInvoiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDraft(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public InvoiceDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasRole('FINANCE_APPROVER')")
    public InvoiceDto issue(@PathVariable UUID id) {
        return service.issue(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('FINANCE_APPROVER')")
    public InvoiceDto cancel(@PathVariable UUID id, @RequestParam String reason) {
        return service.cancel(id, reason);
    }
}
```

### 6.9 The full happy path so far

```bash
# 1. Create a customer (returns customer id)
curl -u clerk:clerk -X POST http://localhost:8080/api/finance/customers \
  -H 'Content-Type: application/json' \
  -d '{"code":"CUST-0001","name":"Acme Corp","gstin":"33ABCDE1234F1Z5"}'

# 2. Get the revenue account id (e.g. 4100 Service Revenue)
curl -u clerk:clerk http://localhost:8080/api/finance/accounts/by-code/4100

# 3. Create a draft invoice with one line — ₹10,000 + 18% GST = ₹11,800
curl -u clerk:clerk -X POST http://localhost:8080/api/finance/invoices \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId":"<customer-uuid>",
    "invoiceDate":"2026-05-27","dueDate":"2026-06-26",
    "lines":[{
      "description":"Consulting services - May",
      "quantity":1,"unitPrice":10000,"taxRate":18,
      "revenueAccountId":"<revenue-uuid>"
    }]
  }'

# 4. Issue it (creates the JE)
curl -u approver:approver -X POST http://localhost:8080/api/finance/invoices/<invoice-id>/issue

# 5. Confirm the JE
curl -u clerk:clerk http://localhost:8080/api/finance/journal-entries/<je-id>
```

You should see a 3-line JE: DR Accounts Receivable 11,800 / CR Service Revenue 10,000 / CR GST Output Payable 1,800. Total debits = total credits = 11,800. ✓

---

## 7. Phase 4 — Payments

Payments are subtle. Three things to get right:

1. **Idempotency** — clients may retry. The same idempotency key must never create two payments.
2. **Concurrency on invoice balances** — pessimistic lock so you can't allocate the same invoice twice in parallel.
3. **Allocation accuracy** — sum of allocations must equal payment amount, and no allocation can exceed an invoice's balance due.

### 7.1 Entities

`payment/domain/Payment.java`:

```java
package com.company.app.finance.payment.domain;

import com.company.app.finance.ledger.domain.Account;
import com.company.app.finance.master.customer.domain.Customer;
import com.company.app.finance.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "payments",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_number", columnNames = "payment_number"),
        @UniqueConstraint(name = "uk_payment_idem",   columnNames = "idempotency_key")
    },
    indexes = {
        @Index(name = "idx_payment_customer", columnList = "customer_id"),
        @Index(name = "idx_payment_date",     columnList = "payment_date")
    })
@Getter @Setter
@NoArgsConstructor
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_number", nullable = false, length = 30)
    private String paymentNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_account_id", nullable = false)
    private Account bankAccount;            // which ASSET account got the cash

    @Column(name = "external_reference", length = 100)
    private String externalReference;       // UTR, cheque number, etc.

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(length = 500)
    private String notes;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true,
        fetch = FetchType.LAZY)
    private List<PaymentAllocation> allocations = new ArrayList<>();

    public void addAllocation(PaymentAllocation a) {
        a.setPayment(this);
        this.allocations.add(a);
    }
}
```

`payment/domain/PaymentAllocation.java`:

```java
package com.company.app.finance.payment.domain;

import com.company.app.finance.invoice.domain.Invoice;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "payment_allocations",
    indexes = {
        @Index(name = "idx_palloc_payment", columnList = "payment_id"),
        @Index(name = "idx_palloc_invoice", columnList = "invoice_id")
    })
@Getter @Setter
@NoArgsConstructor
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount;
}
```

### 7.2 Migration

`src/main/resources/db/migration/V6__payment.sql`:

```sql
CREATE TABLE payments (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    payment_number        VARCHAR(30)  NOT NULL,
    customer_id           UUID         NOT NULL,
    payment_date          DATE         NOT NULL,
    amount                DECIMAL(19,4) NOT NULL,
    bank_account_id       UUID         NOT NULL,
    external_reference    VARCHAR(100),
    idempotency_key       VARCHAR(100),
    journal_entry_id      UUID,
    notes                 VARCHAR(500),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT current_timestamp(),
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_payment_number UNIQUE (payment_number),
    CONSTRAINT uk_payment_idem   UNIQUE (idempotency_key),
    CONSTRAINT fk_payment_customer FOREIGN KEY (customer_id)      REFERENCES customers(id),
    CONSTRAINT fk_payment_bank     FOREIGN KEY (bank_account_id)  REFERENCES accounts(id),
    CONSTRAINT fk_payment_je       FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id),
    CONSTRAINT ck_payment_amount   CHECK (amount > 0)
);

CREATE INDEX idx_payment_customer ON payments(customer_id);
CREATE INDEX idx_payment_date     ON payments(payment_date);

CREATE TABLE payment_allocations (
    id                UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    payment_id        UUID          NOT NULL,
    invoice_id        UUID          NOT NULL,
    allocated_amount  DECIMAL(19,4) NOT NULL,

    CONSTRAINT fk_palloc_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT fk_palloc_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id),
    CONSTRAINT ck_palloc_amount  CHECK (allocated_amount > 0)
);

CREATE INDEX idx_palloc_payment ON payment_allocations(payment_id);
CREATE INDEX idx_palloc_invoice ON payment_allocations(invoice_id);
```

### 7.3 DTOs

`payment/dto/RecordPaymentRequest.java`:

```java
package com.company.app.finance.payment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RecordPaymentRequest(
    @NotNull UUID customerId,
    @NotNull LocalDate paymentDate,
    @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,
    @NotNull UUID bankAccountId,
    @Size(max = 100) String externalReference,
    @Size(max = 500) String notes,
    @NotNull @Size(min = 1) @Valid List<AllocationRequest> allocations
) {
    public record AllocationRequest(
        @NotNull UUID invoiceId,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal allocatedAmount
    ) {}
}
```

`payment/dto/PaymentDto.java`:

```java
package com.company.app.finance.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PaymentDto(
    UUID id, String paymentNumber,
    UUID customerId, String customerName,
    LocalDate paymentDate, BigDecimal amount,
    UUID bankAccountId, String bankAccountCode,
    String externalReference, UUID journalEntryId, String notes,
    List<AllocationDto> allocations
) {
    public record AllocationDto(
        UUID id, UUID invoiceId, String invoiceNumber, BigDecimal allocatedAmount
    ) {}
}
```

### 7.4 Repository

`payment/repository/PaymentRepository.java`:

```java
package com.company.app.finance.payment.repository;

import com.company.app.finance.payment.domain.Payment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {"allocations", "allocations.invoice", "customer", "bankAccount"})
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdWithDetails(@Param("id") UUID id);
}
```

### 7.5 Mapper

`payment/mapper/PaymentMapper.java`:

```java
package com.company.app.finance.payment.mapper;

import com.company.app.finance.payment.domain.Payment;
import com.company.app.finance.payment.domain.PaymentAllocation;
import com.company.app.finance.payment.dto.PaymentDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(source = "customer.id",       target = "customerId")
    @Mapping(source = "customer.name",     target = "customerName")
    @Mapping(source = "bankAccount.id",    target = "bankAccountId")
    @Mapping(source = "bankAccount.code",  target = "bankAccountCode")
    PaymentDto toDto(Payment p);

    @Mapping(source = "invoice.id",            target = "invoiceId")
    @Mapping(source = "invoice.invoiceNumber", target = "invoiceNumber")
    PaymentDto.AllocationDto toAllocationDto(PaymentAllocation a);
}
```

### 7.6 Payment service

`payment/service/PaymentService.java`:

```java
package com.company.app.finance.payment.service;

import com.company.app.finance.invoice.domain.Invoice;
import com.company.app.finance.invoice.domain.InvoiceStatus;
import com.company.app.finance.invoice.repository.InvoiceRepository;
import com.company.app.finance.ledger.domain.Account;
import com.company.app.finance.ledger.domain.AccountType;
import com.company.app.finance.ledger.domain.JournalEntry;
import com.company.app.finance.ledger.dto.PostJournalRequest;
import com.company.app.finance.ledger.dto.PostJournalRequest.LineRequest;
import com.company.app.finance.ledger.repository.AccountRepository;
import com.company.app.finance.ledger.service.JournalPostingService;
import com.company.app.finance.master.customer.domain.Customer;
import com.company.app.finance.master.customer.repository.CustomerRepository;
import com.company.app.finance.payment.domain.Payment;
import com.company.app.finance.payment.domain.PaymentAllocation;
import com.company.app.finance.payment.dto.PaymentDto;
import com.company.app.finance.payment.dto.RecordPaymentRequest;
import com.company.app.finance.payment.mapper.PaymentMapper;
import com.company.app.finance.payment.repository.PaymentRepository;
import com.company.app.finance.shared.exception.AccountNotFoundException;
import com.company.app.finance.shared.exception.DuplicateIdempotencyKeyException;
import com.company.app.finance.shared.exception.InvalidJournalException;
import com.company.app.finance.shared.money.MoneyUtils;
import com.company.app.finance.shared.numbering.EntryNumberGenerator;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final CustomerRepository customerRepo;
    private final InvoiceRepository invoiceRepo;
    private final AccountRepository accountRepo;
    private final JournalPostingService postingService;
    private final EntryNumberGenerator numberGen;
    private final PaymentMapper mapper;

    private static final String AR_ACCOUNT_CODE = "1100";

    @Transactional(readOnly = true)
    public PaymentDto findById(UUID id) {
        return mapper.toDto(paymentRepo.findByIdWithDetails(id)
            .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + id)));
    }

    @Transactional
    public PaymentDto record(RecordPaymentRequest req, String idempotencyKey) {
        // 1. Idempotency check (returns existing payment if key was already used)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Payment> existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency hit: returning existing payment {}", existing.get().getId());
                return mapper.toDto(existing.get());
            }
        }

        // 2. Validate sum of allocations == payment amount
        BigDecimal sumAlloc = req.allocations().stream()
            .map(a -> MoneyUtils.normalize(a.allocatedAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = MoneyUtils.normalize(req.amount());

        if (sumAlloc.compareTo(amount) != 0) {
            throw new InvalidJournalException(
                "Sum of allocations (" + sumAlloc + ") does not equal payment amount (" + amount + ")");
        }

        // 3. Load customer + bank account
        Customer customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> new EntityNotFoundException("Customer not found: " + req.customerId()));
        Account bank = accountRepo.findById(req.bankAccountId())
            .orElseThrow(() -> new AccountNotFoundException(req.bankAccountId()));
        if (bank.getType() != AccountType.ASSET) {
            throw new InvalidJournalException(
                "Payment account must be ASSET type: " + bank.getCode());
        }
        if (!bank.isActive()) {
            throw new InvalidJournalException("Bank account is inactive: " + bank.getCode());
        }

        // 4. Lock and validate each invoice (pessimistic write lock)
        List<Invoice> lockedInvoices = new ArrayList<>();
        Map<UUID, BigDecimal> allocByInvoice = new LinkedHashMap<>();
        for (var ar : req.allocations()) {
            allocByInvoice.merge(ar.invoiceId(),
                MoneyUtils.normalize(ar.allocatedAmount()), BigDecimal::add);
        }

        for (var e : allocByInvoice.entrySet()) {
            Invoice inv = invoiceRepo.findByIdForUpdate(e.getKey())
                .orElseThrow(() -> new EntityNotFoundException("Invoice not found: " + e.getKey()));
            if (!inv.getCustomer().getId().equals(customer.getId())) {
                throw new InvalidJournalException(
                    "Invoice " + inv.getInvoiceNumber() + " does not belong to customer " + customer.getCode());
            }
            if (inv.getStatus() != InvoiceStatus.ISSUED &&
                inv.getStatus() != InvoiceStatus.PARTIALLY_PAID) {
                throw new InvalidJournalException(
                    "Invoice " + inv.getInvoiceNumber() + " is not open (status: " + inv.getStatus() + ")");
            }
            BigDecimal allocation = e.getValue();
            BigDecimal balance = inv.balanceDue();
            if (allocation.compareTo(balance) > 0) {
                throw new InvalidJournalException(
                    "Allocation " + allocation + " exceeds balance " + balance +
                    " for invoice " + inv.getInvoiceNumber());
            }
            lockedInvoices.add(inv);
        }

        // 5. Build the payment
        Payment payment = new Payment();
        payment.setPaymentNumber(numberGen.next("PAY"));
        payment.setCustomer(customer);
        payment.setPaymentDate(req.paymentDate());
        payment.setAmount(amount);
        payment.setBankAccount(bank);
        payment.setExternalReference(req.externalReference());
        payment.setIdempotencyKey(idempotencyKey);
        payment.setNotes(req.notes());

        for (Invoice inv : lockedInvoices) {
            BigDecimal alloc = allocByInvoice.get(inv.getId());
            PaymentAllocation pa = new PaymentAllocation();
            pa.setInvoice(inv);
            pa.setAllocatedAmount(alloc);
            payment.addAllocation(pa);

            // Update invoice balance & status
            BigDecimal newPaid = MoneyUtils.normalize(inv.getAmountPaid().add(alloc));
            inv.setAmountPaid(newPaid);
            if (newPaid.compareTo(inv.getTotal()) == 0) {
                inv.setStatus(InvoiceStatus.PAID);
            } else {
                inv.setStatus(InvoiceStatus.PARTIALLY_PAID);
            }
        }

        // 6. Post JE: DR Bank, CR Accounts Receivable
        Account ar = accountRepo.findByCode(AR_ACCOUNT_CODE)
            .orElseThrow(() -> new AccountNotFoundException(AR_ACCOUNT_CODE));

        List<LineRequest> jeLines = List.of(
            new LineRequest(bank.getId(), amount, BigDecimal.ZERO,
                "Payment received - " + payment.getPaymentNumber()),
            new LineRequest(ar.getId(), BigDecimal.ZERO, amount,
                "AR settled - " + payment.getPaymentNumber())
        );

        PostJournalRequest jeReq = new PostJournalRequest(
            payment.getPaymentDate(),
            "Payment " + payment.getPaymentNumber() + " from " + customer.getName(),
            payment.getPaymentNumber(),
            jeLines
        );

        try {
            JournalEntry je = postingService.postInternal(jeReq, "PAYMENT", null);
            payment.setJournalEntryId(je.getId());
            Payment saved = paymentRepo.save(payment);
            log.info("Recorded payment {} (₹{}) from {} (JE: {})",
                saved.getPaymentNumber(), amount, customer.getCode(), je.getEntryNumber());
            return mapper.toDto(saved);
        } catch (DataIntegrityViolationException ex) {
            // Race condition: another request with same idempotency key landed first
            if (idempotencyKey != null) {
                return paymentRepo.findByIdempotencyKey(idempotencyKey)
                    .map(mapper::toDto)
                    .orElseThrow(() -> new DuplicateIdempotencyKeyException(idempotencyKey, "unknown"));
            }
            throw ex;
        }
    }
}
```

**The two concurrency guards:**

1. **Pessimistic lock on invoices** via `findByIdForUpdate` — prevents two parallel payments from over-allocating against the same invoice. Each will see the *current* balance and fail validation if they exceed it.
2. **Unique constraint on `idempotency_key`** — the DB itself rejects a duplicate. The catch-and-return pattern handles the race where two requests with the same key arrive concurrently.

### 7.7 Controller

`payment/web/PaymentController.java`:

```java
package com.company.app.finance.payment.web;

import com.company.app.finance.payment.dto.PaymentDto;
import com.company.app.finance.payment.dto.RecordPaymentRequest;
import com.company.app.finance.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/finance/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService service;

    @PostMapping
    @PreAuthorize("hasRole('FINANCE_APPROVER')")
    public ResponseEntity<PaymentDto> record(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RecordPaymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.record(req, idempotencyKey));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public PaymentDto get(@PathVariable UUID id) {
        return service.findById(id);
    }
}
```

### 7.8 End-to-end test

```bash
# Record a payment that fully settles the ₹11,800 invoice
curl -u approver:approver -X POST http://localhost:8080/api/finance/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pay-20260527-001' \
  -d '{
    "customerId":"<customer-uuid>",
    "paymentDate":"2026-05-27",
    "amount":11800.00,
    "bankAccountId":"<bank-account-uuid>",
    "externalReference":"NEFT/UTR123456",
    "allocations":[
      {"invoiceId":"<invoice-uuid>","allocatedAmount":11800.00}
    ]
  }'
```

Invoice should now be `PAID`. Sending the same request again with the same idempotency key returns the same payment (HTTP 201 with same body), never creates a duplicate.

---


## 8. Phase 5 — Reports

Reports are queries over `journal_entry_lines` joined to `accounts`, filtered by date and POSTED status. We use native SQL because:
- It maps cleanly to the SQL accountants will recognize
- It's faster than JPQL for aggregations
- CockroachDB can plan it well

All three reports below filter on `status = 'POSTED'`. **Never include DRAFT or REVERSED entries in financial reports.**

### 8.1 Sign convention recap

When computing balances:
- **Debit-normal accounts** (ASSET, EXPENSE): balance = SUM(debit) − SUM(credit). Positive = normal.
- **Credit-normal accounts** (LIABILITY, EQUITY, INCOME): balance = SUM(credit) − SUM(debit). Positive = normal.

For Trial Balance we show debit and credit columns separately. For P&L and Balance Sheet we show natural balances.

### 8.2 DTOs

`report/dto/TrialBalanceRow.java`:

```java
package com.company.app.finance.report.dto;

import com.company.app.finance.ledger.domain.AccountType;
import java.math.BigDecimal;

public record TrialBalanceRow(
    String accountCode,
    String accountName,
    AccountType type,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    BigDecimal debitBalance,    // populated if type is debit-normal and balance > 0
    BigDecimal creditBalance    // populated if type is credit-normal and balance > 0
) {}
```

`report/dto/TrialBalanceReport.java`:

```java
package com.company.app.finance.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrialBalanceReport(
    LocalDate asOfDate,
    List<TrialBalanceRow> rows,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    boolean balanced               // totalDebit equals totalCredit — invariant
) {}
```

`report/dto/ProfitAndLossReport.java`:

```java
package com.company.app.finance.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProfitAndLossReport(
    LocalDate fromDate,
    LocalDate toDate,
    List<AccountLine> income,
    BigDecimal totalIncome,
    List<AccountLine> expense,
    BigDecimal totalExpense,
    BigDecimal netProfit
) {
    public record AccountLine(String accountCode, String accountName, BigDecimal amount) {}
}
```

`report/dto/BalanceSheetReport.java`:

```java
package com.company.app.finance.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalanceSheetReport(
    LocalDate asOfDate,
    List<AccountLine> assets,
    BigDecimal totalAssets,
    List<AccountLine> liabilities,
    BigDecimal totalLiabilities,
    List<AccountLine> equity,
    BigDecimal totalEquity,
    BigDecimal retainedEarningsForPeriod,   // P&L bottom line up to asOfDate
    BigDecimal totalLiabilitiesAndEquity,
    boolean balanced                         // assets == liabilities + equity
) {
    public record AccountLine(String accountCode, String accountName, BigDecimal amount) {}
}
```

### 8.3 Report service

`report/service/ReportService.java`:

```java
package com.company.app.finance.report.service;

import com.company.app.finance.ledger.domain.AccountType;
import com.company.app.finance.report.dto.*;
import com.company.app.finance.shared.money.MoneyUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final EntityManager em;

    // ============================================================
    // Trial Balance
    // ============================================================

    @Transactional(readOnly = true)
    public TrialBalanceReport trialBalance(LocalDate asOfDate) {
        String sql = """
            SELECT a.code, a.name, a.type,
                   COALESCE(SUM(l.debit), 0)  AS total_debit,
                   COALESCE(SUM(l.credit), 0) AS total_credit
            FROM accounts a
            LEFT JOIN journal_entry_lines l ON l.account_id = a.id
            LEFT JOIN journal_entries je    ON je.id = l.journal_entry_id
                  AND je.status = 'POSTED'
                  AND je.entry_date <= :asOf
            GROUP BY a.code, a.name, a.type
            HAVING COALESCE(SUM(l.debit), 0) <> 0
                OR COALESCE(SUM(l.credit), 0) <> 0
            ORDER BY a.code
            """;

        @SuppressWarnings("unchecked")
        List<Object[]> raw = em.createNativeQuery(sql)
            .setParameter("asOf", asOfDate)
            .getResultList();

        List<TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Object[] r : raw) {
            String code = (String) r[0];
            String name = (String) r[1];
            AccountType type = AccountType.valueOf((String) r[2]);
            BigDecimal td = toBig(r[3]);
            BigDecimal tc = toBig(r[4]);

            // Net to one side based on natural balance
            BigDecimal net = td.subtract(tc);
            BigDecimal dr = BigDecimal.ZERO;
            BigDecimal cr = BigDecimal.ZERO;
            if (type.isDebitNormal()) {
                if (net.signum() >= 0) dr = net; else cr = net.negate();
            } else {
                if (net.signum() <= 0) cr = net.negate(); else dr = net;
            }

            totalDebit  = totalDebit.add(dr);
            totalCredit = totalCredit.add(cr);

            rows.add(new TrialBalanceRow(code, name, type,
                MoneyUtils.forDisplay(td), MoneyUtils.forDisplay(tc),
                MoneyUtils.forDisplay(dr), MoneyUtils.forDisplay(cr)));
        }

        totalDebit  = MoneyUtils.forDisplay(totalDebit);
        totalCredit = MoneyUtils.forDisplay(totalCredit);
        boolean balanced = totalDebit.compareTo(totalCredit) == 0;

        if (!balanced) {
            log.error("TRIAL BALANCE DOES NOT BALANCE as of {}: dr={} cr={}",
                asOfDate, totalDebit, totalCredit);
        }

        return new TrialBalanceReport(asOfDate, rows, totalDebit, totalCredit, balanced);
    }

    // ============================================================
    // Profit & Loss
    // ============================================================

    @Transactional(readOnly = true)
    public ProfitAndLossReport profitAndLoss(LocalDate fromDate, LocalDate toDate) {
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("toDate cannot be before fromDate");
        }

        // Income accounts: balance = credit - debit (credit-normal)
        // Expense accounts: balance = debit - credit (debit-normal)
        String sql = """
            SELECT a.code, a.name, a.type,
                   COALESCE(SUM(l.debit), 0)  AS total_debit,
                   COALESCE(SUM(l.credit), 0) AS total_credit
            FROM accounts a
            LEFT JOIN journal_entry_lines l ON l.account_id = a.id
            LEFT JOIN journal_entries je    ON je.id = l.journal_entry_id
                  AND je.status = 'POSTED'
                  AND je.entry_date BETWEEN :from AND :to
            WHERE a.type IN ('INCOME', 'EXPENSE')
            GROUP BY a.code, a.name, a.type
            ORDER BY a.code
            """;

        @SuppressWarnings("unchecked")
        List<Object[]> raw = em.createNativeQuery(sql)
            .setParameter("from", fromDate)
            .setParameter("to", toDate)
            .getResultList();

        List<ProfitAndLossReport.AccountLine> income = new ArrayList<>();
        List<ProfitAndLossReport.AccountLine> expense = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;

        for (Object[] r : raw) {
            String code = (String) r[0];
            String name = (String) r[1];
            AccountType type = AccountType.valueOf((String) r[2]);
            BigDecimal td = toBig(r[3]);
            BigDecimal tc = toBig(r[4]);

            if (type == AccountType.INCOME) {
                BigDecimal balance = tc.subtract(td);
                if (balance.signum() != 0) {
                    income.add(new ProfitAndLossReport.AccountLine(
                        code, name, MoneyUtils.forDisplay(balance)));
                    totalIncome = totalIncome.add(balance);
                }
            } else {
                BigDecimal balance = td.subtract(tc);
                if (balance.signum() != 0) {
                    expense.add(new ProfitAndLossReport.AccountLine(
                        code, name, MoneyUtils.forDisplay(balance)));
                    totalExpense = totalExpense.add(balance);
                }
            }
        }

        BigDecimal netProfit = MoneyUtils.forDisplay(totalIncome.subtract(totalExpense));
        return new ProfitAndLossReport(
            fromDate, toDate,
            income,  MoneyUtils.forDisplay(totalIncome),
            expense, MoneyUtils.forDisplay(totalExpense),
            netProfit
        );
    }

    // ============================================================
    // Balance Sheet
    // ============================================================

    @Transactional(readOnly = true)
    public BalanceSheetReport balanceSheet(LocalDate asOfDate) {
        String sql = """
            SELECT a.code, a.name, a.type,
                   COALESCE(SUM(l.debit), 0)  AS total_debit,
                   COALESCE(SUM(l.credit), 0) AS total_credit
            FROM accounts a
            LEFT JOIN journal_entry_lines l ON l.account_id = a.id
            LEFT JOIN journal_entries je    ON je.id = l.journal_entry_id
                  AND je.status = 'POSTED'
                  AND je.entry_date <= :asOf
            WHERE a.type IN ('ASSET', 'LIABILITY', 'EQUITY')
            GROUP BY a.code, a.name, a.type
            ORDER BY a.code
            """;

        @SuppressWarnings("unchecked")
        List<Object[]> raw = em.createNativeQuery(sql)
            .setParameter("asOf", asOfDate)
            .getResultList();

        List<BalanceSheetReport.AccountLine> assets = new ArrayList<>();
        List<BalanceSheetReport.AccountLine> liabilities = new ArrayList<>();
        List<BalanceSheetReport.AccountLine> equity = new ArrayList<>();
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;

        for (Object[] r : raw) {
            String code = (String) r[0];
            String name = (String) r[1];
            AccountType type = AccountType.valueOf((String) r[2]);
            BigDecimal td = toBig(r[3]);
            BigDecimal tc = toBig(r[4]);

            switch (type) {
                case ASSET -> {
                    BigDecimal balance = td.subtract(tc);
                    if (balance.signum() != 0) {
                        assets.add(new BalanceSheetReport.AccountLine(
                            code, name, MoneyUtils.forDisplay(balance)));
                        totalAssets = totalAssets.add(balance);
                    }
                }
                case LIABILITY -> {
                    BigDecimal balance = tc.subtract(td);
                    if (balance.signum() != 0) {
                        liabilities.add(new BalanceSheetReport.AccountLine(
                            code, name, MoneyUtils.forDisplay(balance)));
                        totalLiabilities = totalLiabilities.add(balance);
                    }
                }
                case EQUITY -> {
                    BigDecimal balance = tc.subtract(td);
                    if (balance.signum() != 0) {
                        equity.add(new BalanceSheetReport.AccountLine(
                            code, name, MoneyUtils.forDisplay(balance)));
                        totalEquity = totalEquity.add(balance);
                    }
                }
                default -> { /* ignore */ }
            }
        }

        // Net profit/loss for the period flows into retained earnings
        // For MVP we treat "the period" as everything up to asOfDate within the current FY
        LocalDate fyStart = currentFyStart(asOfDate);
        ProfitAndLossReport pnl = profitAndLoss(fyStart, asOfDate);
        BigDecimal retained = pnl.netProfit();
        totalEquity = totalEquity.add(retained);
        if (retained.signum() != 0) {
            equity.add(new BalanceSheetReport.AccountLine(
                "RE-CURR", "Retained Earnings (Current Period)", retained));
        }

        BigDecimal totalAssetsDisplay = MoneyUtils.forDisplay(totalAssets);
        BigDecimal totalLiabDisplay   = MoneyUtils.forDisplay(totalLiabilities);
        BigDecimal totalEquityDisplay = MoneyUtils.forDisplay(totalEquity);
        BigDecimal totalLE = totalLiabDisplay.add(totalEquityDisplay);
        boolean balanced = totalAssetsDisplay.compareTo(totalLE) == 0;

        if (!balanced) {
            log.error("BALANCE SHEET DOES NOT BALANCE as of {}: assets={} liab+equity={}",
                asOfDate, totalAssetsDisplay, totalLE);
        }

        return new BalanceSheetReport(
            asOfDate,
            assets,      totalAssetsDisplay,
            liabilities, totalLiabDisplay,
            equity,      totalEquityDisplay,
            retained,
            totalLE,
            balanced
        );
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }

    private static LocalDate currentFyStart(LocalDate d) {
        // Indian FY: April 1 of the current FY
        if (d.getMonthValue() >= 4) {
            return LocalDate.of(d.getYear(), 4, 1);
        }
        return LocalDate.of(d.getYear() - 1, 4, 1);
    }
}
```

### 8.4 Controller

`report/web/ReportController.java`:

```java
package com.company.app.finance.report.web;

import com.company.app.finance.report.dto.BalanceSheetReport;
import com.company.app.finance.report.dto.ProfitAndLossReport;
import com.company.app.finance.report.dto.TrialBalanceReport;
import com.company.app.finance.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/finance/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService service;

    @GetMapping("/trial-balance")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public TrialBalanceReport trialBalance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return service.trialBalance(asOfDate);
    }

    @GetMapping("/profit-and-loss")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public ProfitAndLossReport profitAndLoss(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return service.profitAndLoss(fromDate, toDate);
    }

    @GetMapping("/balance-sheet")
    @PreAuthorize("hasRole('FINANCE_CLERK')")
    public BalanceSheetReport balanceSheet(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return service.balanceSheet(asOfDate);
    }
}
```

### 8.5 Validating the reports

After running the full happy-path (issue invoice → record payment), call each report:

```bash
# Trial Balance: must be balanced
curl -u clerk:clerk "http://localhost:8080/api/finance/reports/trial-balance?asOfDate=2026-05-31"

# Expected non-zero accounts:
#   1002 Bank             DR 11,800
#   2200 GST Output       CR  1,800
#   4100 Service Revenue  CR 10,000
# Totals: DR 11,800 = CR 11,800 ✓

# P&L for May 2026
curl -u clerk:clerk "http://localhost:8080/api/finance/reports/profit-and-loss?fromDate=2026-05-01&toDate=2026-05-31"
# Income: 10,000   Expense: 0   Net: 10,000

# Balance Sheet
curl -u clerk:clerk "http://localhost:8080/api/finance/reports/balance-sheet?asOfDate=2026-05-31"
# Assets: 11,800   Liabilities: 1,800   Equity: 10,000 (retained earnings)
# Total: 11,800 = 11,800 ✓
```

If `balanced: false` ever appears in a response, you have a posting bug. Fix it immediately — every later report compounds the error.

---

## 9. CockroachDB-Specific Concerns

### 9.1 Transaction retries

CockroachDB uses SERIALIZABLE isolation. Concurrent transactions that conflict will get aborted with SQL state `40001` (the famous "retry transaction" error). You **must** retry. Add an interceptor.

`shared/db/RetryableTransactionAspect.java`:

```java
package com.company.app.finance.shared.db;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

@Slf4j
@Aspect
@Component
@Order(0)  // Run BEFORE Spring's @Transactional aspect
public class RetryableTransactionAspect {

    private static final int MAX_RETRIES = 5;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object retryOnSerializationFailure(ProceedingJoinPoint pjp) throws Throwable {
        int attempt = 0;
        while (true) {
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                if (isRetryable(t) && attempt < MAX_RETRIES) {
                    attempt++;
                    long backoffMs = (long) (Math.pow(2, attempt) * 10);
                    log.warn("Retryable transaction failure (attempt {} of {}): {}; sleeping {}ms",
                        attempt, MAX_RETRIES, t.getMessage(), backoffMs);
                    Thread.sleep(backoffMs);
                    continue;
                }
                throw t;
            }
        }
    }

    private boolean isRetryable(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException sql) {
                String sqlState = sql.getSQLState();
                if ("40001".equals(sqlState) || "40P01".equals(sqlState)) return true;
            }
            if (cur instanceof TransientDataAccessException) return true;
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("restart transaction") ||
                                msg.contains("TransactionRetryError"))) return true;
            cur = cur.getCause();
        }
        return false;
    }
}
```

Add `spring-boot-starter-aop` to `pom.xml` if it isn't already in via web. **Caveat:** wrapping `@Transactional` with retry is opinionated. An alternative is per-call retry helpers. For an MVP, the aspect is fine.

### 9.2 UUID generation

We use `gen_random_uuid()` in DDL defaults and `GenerationType.UUID` in JPA. Hibernate 6 generates the UUID client-side, which keeps inserts in the same transaction without an extra round-trip. Both approaches work; the JPA side is what actually wins because we set the ID before flush.

### 9.3 `BIGINT` for monetary values is wrong

Some CockroachDB tutorials use `BIGINT` for money (storing paise/cents). **Don't.** Stick with `DECIMAL(19,4)` everywhere:
- It's exact (no float drift).
- Reports and aggregations stay readable.
- CockroachDB handles DECIMAL well; the perf difference is negligible at MVP scale.

### 9.4 `AS OF SYSTEM TIME` for reports

CockroachDB lets you read consistent snapshots from the recent past — fantastic for reports that take a long time, because they don't block writes. For MVP this is overkill, but **good to know**:

```sql
SELECT ... AS OF SYSTEM TIME '-10s' FROM journal_entry_lines ...
```

You can't easily mix this with JPA. Use native queries when you want it.

### 9.5 `application.yml` connection URL options

For local insecure:
```
jdbc:postgresql://localhost:26257/finance?sslmode=disable
```

For CockroachDB Serverless / Dedicated, you'll need SSL:
```
jdbc:postgresql://<host>:26257/finance?sslmode=verify-full&sslrootcert=<path>
```

### 9.6 Schema migrations on CockroachDB

CockroachDB schema changes are online but not free. For MVP, this isn't a concern. Just know that as your tables grow, adding indexes via Flyway in production needs to be done thoughtfully (use `CREATE INDEX CONCURRENTLY` when possible — CockroachDB does this implicitly for many DDL operations).

### 9.7 Flyway dialect

Flyway's `flyway-database-postgresql` works with CockroachDB because CockroachDB speaks the PostgreSQL wire protocol. You may see warnings; they're safe to ignore. If you hit issues, set:

```yaml
spring:
  flyway:
    postgresql:
      transactional-lock: false
```

---

## 10. Testing Strategy

You cannot ship a finance module without integration tests. Unit tests on calculations help, but **integration tests against a real CockroachDB** are what prove the system works.

### 10.1 Base test class with Testcontainers

`src/test/java/com/company/app/finance/AbstractIntegrationTest.java`:

```java
package com.company.app.finance;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static CockroachContainer cockroach = new CockroachContainer("cockroachdb/cockroach:latest-v23.2");
}
```

`src/test/resources/application-test.yml`:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        dialect: org.hibernate.dialect.CockroachDialect
    show-sql: false
  flyway:
    enabled: true
```

`ServiceConnection` is the Spring Boot 3.1+ magic — it wires Testcontainers' connection details into your DataSource automatically. No `@DynamicPropertySource` boilerplate needed.

### 10.2 Posting service tests

`src/test/java/com/company/app/finance/ledger/JournalPostingServiceIT.java`:

```java
package com.company.app.finance.ledger;

import com.company.app.finance.AbstractIntegrationTest;
import com.company.app.finance.ledger.domain.Account;
import com.company.app.finance.ledger.dto.PostJournalRequest;
import com.company.app.finance.ledger.dto.PostJournalRequest.LineRequest;
import com.company.app.finance.ledger.dto.ReverseJournalRequest;
import com.company.app.finance.ledger.repository.AccountRepository;
import com.company.app.finance.ledger.service.JournalPostingService;
import com.company.app.finance.shared.exception.InvalidJournalException;
import com.company.app.finance.shared.exception.UnbalancedEntryException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(username = "test-approver", roles = {"FINANCE_APPROVER"})
class JournalPostingServiceIT extends AbstractIntegrationTest {

    @Autowired JournalPostingService postingService;
    @Autowired AccountRepository accountRepo;

    @Test
    void postBalancedEntry_succeeds() {
        Account cash    = accountRepo.findByCode("1001").orElseThrow();
        Account capital = accountRepo.findByCode("3000").orElseThrow();

        var req = new PostJournalRequest(
            LocalDate.now(), "Initial capital", null,
            List.of(
                new LineRequest(cash.getId(),    new BigDecimal("10000"), BigDecimal.ZERO, "Cash in"),
                new LineRequest(capital.getId(), BigDecimal.ZERO, new BigDecimal("10000"), "Capital")
            ));

        var result = postingService.post(req);

        assertThat(result.entryNumber()).startsWith("JE-");
        assertThat(result.lines()).hasSize(2);
        assertThat(result.status().name()).isEqualTo("POSTED");
    }

    @Test
    void postUnbalancedEntry_throws() {
        Account cash    = accountRepo.findByCode("1001").orElseThrow();
        Account capital = accountRepo.findByCode("3000").orElseThrow();

        var req = new PostJournalRequest(
            LocalDate.now(), "Unbalanced", null,
            List.of(
                new LineRequest(cash.getId(),    new BigDecimal("100"), BigDecimal.ZERO, ""),
                new LineRequest(capital.getId(), BigDecimal.ZERO, new BigDecimal("99"), "")
            ));

        assertThatThrownBy(() -> postingService.post(req))
            .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void postLineWithBothDebitAndCredit_throws() {
        Account cash    = accountRepo.findByCode("1001").orElseThrow();
        Account capital = accountRepo.findByCode("3000").orElseThrow();

        var req = new PostJournalRequest(
            LocalDate.now(), "Invalid line", null,
            List.of(
                new LineRequest(cash.getId(),    new BigDecimal("100"), new BigDecimal("100"), ""),
                new LineRequest(capital.getId(), BigDecimal.ZERO, new BigDecimal("100"), "")
            ));

        assertThatThrownBy(() -> postingService.post(req))
            .isInstanceOf(InvalidJournalException.class)
            .hasMessageContaining("cannot have both");
    }

    @Test
    void singleLineEntry_throws() {
        Account cash = accountRepo.findByCode("1001").orElseThrow();
        var req = new PostJournalRequest(
            LocalDate.now(), "Single line", null,
            List.of(new LineRequest(cash.getId(), new BigDecimal("100"), BigDecimal.ZERO, "")));

        assertThatThrownBy(() -> postingService.post(req))
            .isInstanceOf(InvalidJournalException.class)
            .hasMessageContaining("At least 2 lines");
    }

    @Test
    void futureDatedEntry_throws() {
        Account cash    = accountRepo.findByCode("1001").orElseThrow();
        Account capital = accountRepo.findByCode("3000").orElseThrow();

        var req = new PostJournalRequest(
            LocalDate.now().plusDays(1), "Future", null,
            List.of(
                new LineRequest(cash.getId(),    new BigDecimal("100"), BigDecimal.ZERO, ""),
                new LineRequest(capital.getId(), BigDecimal.ZERO, new BigDecimal("100"), "")
            ));

        assertThatThrownBy(() -> postingService.post(req))
            .isInstanceOf(InvalidJournalException.class)
            .hasMessageContaining("future");
    }

    @Test
    void reverseEntry_swapsDebitsAndCredits() {
        Account cash    = accountRepo.findByCode("1001").orElseThrow();
        Account capital = accountRepo.findByCode("3000").orElseThrow();

        var req = new PostJournalRequest(
            LocalDate.now(), "To reverse", null,
            List.of(
                new LineRequest(cash.getId(),    new BigDecimal("500"), BigDecimal.ZERO, ""),
                new LineRequest(capital.getId(), BigDecimal.ZERO, new BigDecimal("500"), "")
            ));
        var original = postingService.post(req);

        var reversal = postingService.reverse(original.id(),
            new ReverseJournalRequest("Test reversal"));

        // The reversal lines should have swapped DR/CR
        var cashLine = reversal.lines().stream()
            .filter(l -> l.accountCode().equals("1001")).findFirst().orElseThrow();
        assertThat(cashLine.credit()).isEqualByComparingTo("500");
        assertThat(cashLine.debit()).isEqualByComparingTo("0");
    }

    /**
     * THE MOST IMPORTANT TEST. If this passes, your invariant holds.
     */
    @Test
    void thousandRandomEntries_totalDebitsEqualTotalCredits() {
        List<Account> accounts = accountRepo.findAll().stream()
            .filter(Account::isActive).limit(10).toList();
        Random rnd = new Random(42);

        for (int i = 0; i < 1000; i++) {
            BigDecimal amount = new BigDecimal(rnd.nextInt(1, 10000));
            Account dr = accounts.get(rnd.nextInt(accounts.size()));
            Account cr;
            do {
                cr = accounts.get(rnd.nextInt(accounts.size()));
            } while (cr.getId().equals(dr.getId()));

            var req = new PostJournalRequest(
                LocalDate.now(), "Random #" + i, null,
                List.of(
                    new LineRequest(dr.getId(), amount, BigDecimal.ZERO, ""),
                    new LineRequest(cr.getId(), BigDecimal.ZERO, amount, "")
                ));
            postingService.post(req);
        }

        // Direct DB check
        var totals = accountRepo.getEntityManager() != null
            ? null
            : null;
        // Use a native query via the entity manager (inject if needed in your test setup)
        // Pseudocode:
        // SELECT SUM(debit), SUM(credit) FROM journal_entry_lines JOIN journal_entries ...
        // assertThat(sumDebit).isEqualByComparingTo(sumCredit);
    }
}
```

For the last test, inject an `EntityManager` and run:

```java
@Autowired EntityManager em;

@Test
void thousandRandomEntries_totalDebitsEqualTotalCredits() {
    // ... post 1000 random entries ...

    Object[] row = (Object[]) em.createNativeQuery(
        "SELECT COALESCE(SUM(l.debit), 0), COALESCE(SUM(l.credit), 0) " +
        "FROM journal_entry_lines l " +
        "JOIN journal_entries je ON je.id = l.journal_entry_id " +
        "WHERE je.status = 'POSTED'")
        .getSingleResult();

    BigDecimal totalDebit  = (BigDecimal) row[0];
    BigDecimal totalCredit = (BigDecimal) row[1];
    assertThat(totalDebit).isEqualByComparingTo(totalCredit);
}
```

### 10.3 End-to-end test

`src/test/java/com/company/app/finance/InvoicePaymentFlowIT.java`:

```java
package com.company.app.finance;

import com.company.app.finance.invoice.dto.CreateInvoiceRequest;
import com.company.app.finance.invoice.dto.InvoiceDto;
import com.company.app.finance.invoice.service.InvoiceService;
import com.company.app.finance.ledger.repository.AccountRepository;
import com.company.app.finance.master.customer.dto.CreateCustomerRequest;
import com.company.app.finance.master.customer.dto.CustomerDto;
import com.company.app.finance.master.customer.service.CustomerService;
import com.company.app.finance.payment.dto.PaymentDto;
import com.company.app.finance.payment.dto.RecordPaymentRequest;
import com.company.app.finance.payment.service.PaymentService;
import com.company.app.finance.report.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@WithMockUser(username = "test-approver",
    roles = {"FINANCE_CLERK", "FINANCE_APPROVER", "FINANCE_ADMIN"})
class InvoicePaymentFlowIT extends AbstractIntegrationTest {

    @Autowired CustomerService customerService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired ReportService reportService;
    @Autowired AccountRepository accountRepo;

    @Test
    void fullFlow_invoiceIssuedPaid_trialBalanceBalances() {
        // 1. Create customer
        CustomerDto customer = customerService.create(new CreateCustomerRequest(
            "CUST-IT-001", "Test Customer", null, null, "test@example.com", null,
            null, null, 30
        ));

        UUID revenueAccountId = accountRepo.findByCode("4100").orElseThrow().getId();
        UUID bankAccountId    = accountRepo.findByCode("1002").orElseThrow().getId();

        // 2. Create draft invoice for ₹10,000 + 18% GST = ₹11,800
        InvoiceDto draft = invoiceService.createDraft(new CreateInvoiceRequest(
            customer.id(), LocalDate.now(), LocalDate.now().plusDays(30), null,
            List.of(new CreateInvoiceRequest.LineRequest(
                "Consulting", new BigDecimal("1"), new BigDecimal("10000"),
                new BigDecimal("18"), revenueAccountId))
        ));
        assertThat(draft.total()).isEqualByComparingTo("11800");
        assertThat(draft.status().name()).isEqualTo("DRAFT");

        // 3. Issue invoice
        InvoiceDto issued = invoiceService.issue(draft.id());
        assertThat(issued.status().name()).isEqualTo("ISSUED");
        assertThat(issued.journalEntryId()).isNotNull();

        // 4. Record full payment
        PaymentDto payment = paymentService.record(
            new RecordPaymentRequest(
                customer.id(), LocalDate.now(), new BigDecimal("11800"),
                bankAccountId, "UTR-TEST-001", null,
                List.of(new RecordPaymentRequest.AllocationRequest(
                    issued.id(), new BigDecimal("11800")))
            ),
            "idem-test-001"
        );
        assertThat(payment.amount()).isEqualByComparingTo("11800");

        // 5. Invoice should now be PAID
        InvoiceDto paid = invoiceService.findById(issued.id());
        assertThat(paid.status().name()).isEqualTo("PAID");
        assertThat(paid.amountPaid()).isEqualByComparingTo("11800");
        assertThat(paid.balanceDue()).isEqualByComparingTo("0");

        // 6. Trial Balance must be balanced
        var tb = reportService.trialBalance(LocalDate.now());
        assertThat(tb.balanced()).isTrue();

        // 7. Idempotency: same key → same payment
        PaymentDto duplicate = paymentService.record(
            new RecordPaymentRequest(
                customer.id(), LocalDate.now(), new BigDecimal("11800"),
                bankAccountId, "UTR-TEST-001", null,
                List.of(new RecordPaymentRequest.AllocationRequest(
                    issued.id(), new BigDecimal("11800")))
            ),
            "idem-test-001"
        );
        assertThat(duplicate.id()).isEqualTo(payment.id());
    }
}
```

### 10.4 Unit tests for money math

`src/test/java/com/company/app/finance/shared/MoneyUtilsTest.java`:

```java
package com.company.app.finance.shared;

import com.company.app.finance.shared.money.MoneyUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyUtilsTest {

    @Test
    void normalize_setsScaleToFour() {
        assertThat(MoneyUtils.normalize(new BigDecimal("10")).scale()).isEqualTo(4);
        assertThat(MoneyUtils.normalize(new BigDecimal("10.123456"))).isEqualByComparingTo("10.1235");
    }

    @Test
    void roundingIsHalfUp() {
        assertThat(MoneyUtils.normalize(new BigDecimal("10.12345"))).isEqualByComparingTo("10.1235");
        assertThat(MoneyUtils.normalize(new BigDecimal("10.12344"))).isEqualByComparingTo("10.1234");
    }

    @Test
    void taxCalculation_18Percent() {
        // ₹10,000 + 18% GST
        BigDecimal subtotal = new BigDecimal("10000");
        BigDecimal taxRate = new BigDecimal("18");
        BigDecimal tax = MoneyUtils.normalize(
            subtotal.multiply(taxRate).divide(new BigDecimal("100")));
        assertThat(tax).isEqualByComparingTo("1800");
        assertThat(subtotal.add(tax)).isEqualByComparingTo("11800");
    }
}
```

### 10.5 Test coverage targets

- **JournalPostingService**: every validation branch, plus the 1000-entry invariant test. Aim for 100% line coverage here.
- **InvoiceService**: createDraft, issue, cancel (each status), tax calculation accuracy.
- **PaymentService**: full settle, partial settle, multi-invoice allocation, idempotency, over-allocation rejection, customer mismatch rejection.
- **ReportService**: trial balance balances, P&L matches expected, balance sheet balances.

Don't chase 100% overall — chase 100% on the **invariant-protecting code**.

---

## 11. Operational Concerns

### 11.1 Actuator endpoints

`spring-boot-starter-actuator` is already in your pom. Useful endpoints in `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus, flyway
  endpoint:
    health:
      show-details: when-authorized
  info:
    env:
      enabled: true
info:
  app:
    name: ${spring.application.name}
    version: @project.version@
```

### 11.2 Structured logging

In production switch to JSON logs (logback + `logstash-logback-encoder`). For MVP, ensure every important event is logged at INFO with structured fields. The `log.info(...)` calls in the services above already give you most of what you need.

### 11.3 Request correlation

Add a filter that puts a request ID into MDC so every log line is traceable:

`config/RequestIdFilter.java`:

```java
package com.company.app.finance.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class RequestIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest hreq = (HttpServletRequest) req;
        HttpServletResponse hres = (HttpServletResponse) res;
        String reqId = hreq.getHeader("X-Request-Id");
        if (reqId == null || reqId.isBlank()) reqId = UUID.randomUUID().toString();
        MDC.put("requestId", reqId);
        hres.setHeader("X-Request-Id", reqId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("requestId");
        }
    }
}
```

Then in `logback-spring.xml` reference `%X{requestId}` in the pattern.

### 11.4 API documentation

springdoc-openapi gives you Swagger UI at `/swagger-ui.html` automatically. Annotate complex endpoints with `@Operation` and `@Schema` for clearer docs.

### 11.5 Database connection pool sizing

For a single-instance MVP, HikariCP at `maximum-pool-size: 20` is fine. CockroachDB recommends keeping pool size moderate (one pool per app instance, not per node). If you scale to multiple instances, total connections shouldn't exceed ~4× number of vCPUs on the CockroachDB cluster.

### 11.6 Production-grade things deferred

- **JWT/OAuth2 instead of HTTP Basic**: integrate Spring Security with your actual auth provider.
- **Rate limiting**: Bucket4j or API gateway.
- **Multi-tenancy**: if you'll host multiple companies, add a `tenant_id` column on every table with a row-level filter via Hibernate's `@Filter`.
- **Backup/restore**: CockroachDB has `BACKUP` and `RESTORE`. Plan PITR before you go live.
- **Observability**: Micrometer + Prometheus + Grafana. Trace JE post latency, payment record latency, report query time.

---

## 12. Day-by-Day Checklist

| Day | Deliverable | Tests to write |
|---|---|---|
| 1 | Project setup, pom.xml, application.yml, package structure, BaseEntity, security skeleton, OpenAPI, MoneyUtils, exceptions, GlobalExceptionHandler, RetryableTransactionAspect | smoke test boots |
| 2 | V1 + V2 migrations, Account entity, AccountRepository, AccountService, AccountController, COA seed (V3) | AccountServiceIT (CRUD) |
| 3 | JournalEntry / JournalEntryLine entities & repositories, EntryNumberGenerator with locking | numbering generator concurrency test |
| 4 | JournalPostingService (post, reverse), JournalEntryController, DTOs, mappers | full JournalPostingServiceIT incl. 1000-entry invariant test |
| 5 | Customer entity, repository, service, controller, V4 migration | CustomerServiceIT |
| 6 | Invoice entities, V5 migration, repository | InvoiceRepository smoke |
| 7 | InvoiceService.createDraft + cancel(DRAFT), mapper, DTOs | InvoiceServiceIT draft tests |
| 8 | InvoiceService.issue (posts JE), InvoiceController | InvoiceServiceIT issue test |
| 9 | Payment entities, V6 migration, idempotency key constraint | PaymentRepository smoke |
| 10 | PaymentService.record with allocation, locking, idempotency, PaymentController | PaymentServiceIT full + idempotency + concurrency |
| 11 | TrialBalance, P&L queries + DTOs | ReportServiceIT TB + P&L |
| 12 | BalanceSheet, ReportController, balance validation | ReportServiceIT BS, end-to-end test |
| 13 | Hardening: error responses, validation messages, OpenAPI annotations, structured logging, MDC | (manual test pass via Postman) |
| 14 | Buffer / polish / docs / Postman collection / README | smoke deploy to staging |

**Strict order rule:** don't move on if Day N's tests aren't green. Bugs in the ledger silently corrupt every downstream report.

---

## 13. Common Pitfalls

These will bite you if you skip them. Each one I've watched cost real money in real systems.

**Using `double` or `float` anywhere near money.** Already covered — never. Even in tests.

**`BigDecimal.equals` instead of `compareTo`.** `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false` because scale differs. Always use `compareTo(...) == 0` for value equality.

**Mutating POSTED entries.** No code path may change a posted entry's lines or amounts. Reversal only. If you find yourself writing an "edit posted entry" endpoint, stop.

**Forgetting `@Transactional` on multi-step writes.** A payment that posts a JE and updates 3 invoices must be one transaction. If the JE post fails partway, invoice updates must roll back.

**`open-in-view: true`.** Default-on in Spring Boot. Catastrophic for finance code — lazy-loading silently runs queries after the controller returns, breaking your transactional guarantees and inflating latency. We set it to `false` in `application.yml`. **Verify** before shipping.

**N+1 queries.** Use `@EntityGraph` on read paths that traverse relationships (we did on `findByIdWithLines`). Watch the SQL logs during integration tests.

**Forgetting to handle CockroachDB retries.** SERIALIZABLE conflicts happen under load. Without retry, your users see random 500s. The aspect we built handles it.

**Computing reports from `JournalEntry` instead of `JournalEntryLine`.** Always sum lines. Always filter by `status = 'POSTED'`.

**Not validating that revenue account is INCOME type.** A clerk picks "Cash" as the revenue account on an invoice line — disastrous. The service-layer check we added in `InvoiceService.createDraft` prevents this.

**Using auto-increment IDs in CockroachDB.** Range hotspots. Use UUIDs.

**Letting users back-date invoices freely.** For MVP we only block future dates. In v2 add a soft window ("no posts older than 30 days") and a strict period-close model.

**Hardcoding control account codes in business logic.** We did this (`AR_ACCOUNT_CODE = "1100"`). It's fine for MVP — promote to a configuration entity in v2.

**Idempotency keys that aren't actually idempotent.** Make sure your unique constraint is on `idempotency_key`, and your service handles both the "found existing" and "concurrent insert collision" paths. We did both.

**Mixing transaction boundaries with REQUIRES_NEW.** Our `EntryNumberGenerator` uses `REQUIRES_NEW` deliberately to never reuse a number. Understand what this means: numbers will have gaps on rollback. If your auditor requires gapless numbering, switch to default propagation and add explicit "voided" tracking.

**Storing display-formatted amounts.** Always store at scale 4. Format at the edge (DTO mapper, JSON output, PDF render). If you store at 2 dp you can't accurately re-derive tax breakdowns.

---

## 14. v2 Roadmap

After MVP, in rough priority order:

1. **Vendor and Bills (AP)** — mirror of Customer + Invoice, but JEs are DR Expense / CR Accounts Payable.
2. **GST features** — CGST/SGST/IGST decision based on customer state, GSTR-1 and GSTR-3B export formats, HSN/SAC on invoice lines.
3. **TDS on payments** — deduct at source from vendor payments above thresholds.
4. **Fiscal period close** — `FiscalPeriod` entity with OPEN/CLOSED status; posting to a closed period throws.
5. **Bank reconciliation** — import bank statement (CSV), match against payments, mark cleared dates.
6. **Credit and debit notes** — proper return / adjustment workflow (replaces "cancel a paid invoice").
7. **Multi-currency** — Currency, ExchangeRate entities; foreign-currency invoices with realized/unrealized FX gain/loss.
8. **Invoice PDF generation** — itext or OpenPDF, with company branding, tax breakdowns, GST notes.
9. **E-invoicing (India)** — IRN generation against the GST portal API, QR code embedding.
10. **Budgeting & variance** — Budget per account per period, variance reports.
11. **Cash flow statement** — derived from BS and P&L per Indian Accounting Standards.
12. **Multi-tenancy** — `tenant_id` everywhere, Hibernate filters or row-level security.
13. **Audit log via Envers** — full row history of every change.
14. **Better numbering** — gapless with explicit void status, per-document-type prefixes per company.

Plan a v2 retrospective after MVP. Some of these will turn out to be unnecessary; others not on this list will become urgent.

---

## Closing notes

A few things to remember as you build:

- **The ledger is sacred.** If you have to choose between shipping a feature and protecting the ledger invariants, protect the ledger. Reports lie if the ledger is wrong.
- **Test like an auditor would.** Your `thousandRandomEntries_totalDebitsEqualTotalCredits` test is non-negotiable. Run it in CI on every PR.
- **Pessimistic locks are your friend in finance.** They're slower than optimistic, but money-moving code prizes correctness over throughput.
- **When in doubt, post and reverse.** Never edit.
- **Layer cleanly.** Documents (invoices, payments) generate journal entries. Reports read journal entries. Don't shortcut by computing report figures from documents — when you start counting deductions, refunds, and credit notes in v2, you'll be glad you didn't.

You now have everything you need to ship the MVP. Start with Phase 0 (setup) on Day 1. Don't skip the tests. Ping if you get stuck on a specific phase and I'll go deeper.
