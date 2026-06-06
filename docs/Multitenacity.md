Implementing multi-tenancy using a **Schema-per-Tenant** strategy in Spring Boot with CockroachDB and Liquibase requires a dynamic approach to data sourcing. Since you want the schema to switch based on the user's active organization, you'll need to intercept the request, identify the tenant, and tell Spring to use the corresponding schema.

Here is the architectural blueprint and implementation steps.

---

## 1. The Strategy: Schema-per-Tenant

In CockroachDB, you will have one database, but multiple schemas (e.g., `org_a`, `org_b`, `public`).

* **Pros:** Data isolation, easy to drop a tenant, shared connection pool.
* **Cons:** Liquibase migrations must run across all schemas.

---

## 2. Step-by-Step Implementation

### A. Tenant Context Holder

You need a way to store the "Current Tenant ID" for the duration of a single API request. Use `ThreadLocal` for this.

```java
public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}

```

### B. Identify the Tenant (Filter/Interceptor)

Extract the organization ID from the request (e.g., a JWT claim or a custom header like `X-Tenant-ID`).

```java
@Component
public class TenantFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        String tenantId = request.getHeader("X-Tenant-ID"); // Or extract from JWT
        TenantContext.setCurrentTenant(tenantId != null ? tenantId : "public");
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}

```

### C. Dynamic Schema Switching (Hibernate)

Hibernate provides the `MultiTenantConnectionProvider` and `CurrentTenantIdentifierResolver` interfaces specifically for this.

1. **Identifier Resolver:** Tells Hibernate which tenant is active.

```java
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver {
    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getCurrentTenant();
        return tenantId != null ? tenantId : "public";
    }

    @Override
    public boolean validateExistingCurrentSessions() { return true; }
}

```

2. **Connection Provider:** This is where you tell CockroachDB to switch schemas. Since CockroachDB is PostgreSQL-compatible, you can issue a `SET search_path TO <tenant_id>`.

```java
@Component
public class MultiTenantConnectionProviderImpl extends AbstractMultiTenantConnectionProvider {
    private final DataSource dataSource;

    public MultiTenantConnectionProviderImpl(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected ConnectionProvider getAnyConnectionProvider() {
        return dataSource::getConnection;
    }

    @Override
    protected ConnectionProvider selectConnectionProvider(String tenantIdentifier) {
        return () -> {
            Connection connection = dataSource.getConnection();
            connection.createStatement().execute("SET search_path TO " + tenantIdentifier);
            return connection;
        };
    }
}

```

---

## 3. Handling Liquibase Migrations

The challenge with Liquibase in multi-tenant setups is ensuring that when you update your code, **all** organization schemas are updated.

Standard Spring Boot Liquibase auto-configuration only runs on one schema. You have two options:

1. **Iterative Migration:** On startup, fetch a list of all organization schemas from your "Master" table and loop through them, running `Liquibase.update()` programmatically for each.
2. **Liquibase Multi-tenant Library:** Use a library like `liquibase-hibernate6` or specialized Spring wrappers that handle the loop for you.

---

## 4. Key CockroachDB Considerations

* **Search Path:** Using `SET search_path` is the most efficient way to switch context without closing/opening new connections.
* **Schema Creation:** When a new organization signs up, your backend must execute `CREATE SCHEMA org_name;` and then trigger the Liquibase migration for that specific schema.

---

### Important Security Note

Always validate the `tenantId` extracted from headers against the user's authorized organizations in your JWT/Database. Never trust the header blindly, or a user could "switch" to an organization they don't belong to.

Would you like me to provide the specific Java code to programmatically run Liquibase migrations across all schemas during startup?