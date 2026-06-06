To implement multi-tenancy with **Spring Boot**, **CockroachDB**, and **Liquibase** using a **Schema-per-Tenant** approach, follow this comprehensive guide.

### The Architecture

1. **Shared Database:** One CockroachDB database.
2. **Separate Schemas:** Each organization gets its own schema (e.g., `org_1`, `org_2`).
3. **Default Schema:** A `public` schema holds the `tenants` table (the master list of organizations).

---

## Step 1: Dependencies

In your `pom.xml`, ensure you have the necessary starters. You need the Liquibase core library to run migrations programmatically.

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.liquibase</groupId>
        <artifactId>liquibase-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId> </dependency>
</dependencies>

```

---

## Step 2: Disable Default Liquibase

We must stop Spring Boot from running Liquibase on startup because the default behavior doesn't know about your multiple schemas.

**application.yml**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:26257/my_db
    username: root
    password: 
  liquibase:
    enabled: false # Critical: We will run this manually
  jpa:
    properties:
      hibernate:
        multiTenancy: SCHEMA
        tenant_identifier_resolver: com.example.config.TenantIdentifierResolver
        multi_tenant_connection_provider: com.example.config.MultiTenantConnectionProviderImpl

```

---

## Step 3: Define the Tenant Context

We use a `ThreadLocal` to store the tenant ID for the duration of the web request.

```java
public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public static void setCurrentTenant(String tenantId) { CURRENT_TENANT.set(tenantId); }
    public static String getCurrentTenant() { return CURRENT_TENANT.get(); }
    public static void clear() { CURRENT_TENANT.remove(); }
}

```

---

## Step 4: Create the Hibernate Resolvers

These two classes tell Hibernate (1) which tenant is active and (2) how to modify the database connection to use that tenant's schema.

### 4.1 Tenant Identifier Resolver

```java
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver {
    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getCurrentTenant();
        return (tenantId != null) ? tenantId : "public";
    }

    @Override
    public boolean validateExistingCurrentSessions() { return true; }
}

```

### 4.2 Connection Provider (The CockroachDB Switch)

This is where we execute `SET search_path` to point the connection to the correct schema.

```java
@Component
public class MultiTenantConnectionProviderImpl extends AbstractMultiTenantConnectionProvider {
    private final DataSource dataSource;

    public MultiTenantConnectionProviderImpl(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected ConnectionProvider getAnyConnectionProvider() { return dataSource::getConnection; }

    @Override
    protected ConnectionProvider selectConnectionProvider(String tenantIdentifier) {
        return () -> {
            Connection connection = dataSource.getConnection();
            try (Statement statement = connection.createStatement()) {
                // CockroachDB uses 'SET search_path' just like Postgres
                statement.execute("SET search_path TO " + tenantIdentifier);
            }
            return connection;
        };
    }
}

```

---

## Step 5: Programmatic Liquibase Runner

Since we disabled auto-Liquibase, we need a bean that initializes the "Master" schema (where organizations are listed) and then loops through every tenant schema to apply updates.

```java
@Configuration
public class LiquibaseConfig {

    @Bean
    public InitializingBean liquibaseRunner(DataSource dataSource) {
        return () -> {
            // 1. Run Liquibase on the "public" schema first (to update tenant list)
            runLiquibase(dataSource, "public");

            // 2. Fetch list of tenants from the now-updated public schema
            List<String> tenants = getTenantsFromDatabase(dataSource);

            // 3. Run Liquibase for each tenant
            for (String tenant : tenants) {
                runLiquibase(dataSource, tenant);
            }
        };
    }

    private void runLiquibase(DataSource dataSource, String schema) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            connection.createStatement().execute("SET search_path TO " + schema);

            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);

            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.xml",
                new ClassLoaderResourceAccessor(), database);
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }

    private List<String> getTenantsFromDatabase(DataSource dataSource) throws SQLException {
        List<String> tenants = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.createStatement().executeQuery("SELECT schema_name FROM public.organizations")) {
            while (rs.next()) {
                tenants.add(rs.getString("schema_name"));
            }
        }
        return tenants;
    }
}

```

---

## Step 6: The Interceptor (Capture the Switch)

Finally, create a Filter to grab the organization ID from the request (headers or JWT) and set it in our `TenantContext`.

```java
@Component
public class TenantInterceptor extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) 
            throws ServletException, IOException {
        
        String tenantId = request.getHeader("X-Tenant-ID"); 
        // Logic: Verify if user has access to this tenantId here!
        
        TenantContext.setCurrentTenant(tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}

```

---

### Summary of Operation

1. **On Startup:** `LiquibaseConfig` runs. It creates/updates the `public` schema, gets the list of orgs, and runs migrations on every org's schema.
2. **On Request:** The `TenantInterceptor` sees `X-Tenant-ID: org_apple`.
3. **Database Call:** Hibernate asks `TenantIdentifierResolver` "Who is the tenant?" It gets `org_apple`.
4. **Connection:** `MultiTenantConnectionProviderImpl` takes a connection from the pool and runs `SET search_path TO org_apple` before the query runs.

Would you like me to show you how to handle the creation of a **brand new** organization schema at runtime when a user signs up?