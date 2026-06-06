# Redis Caching Implementation Guide

## A Comprehensive Learning Resource for Spring Boot + Redis Caching

This document explains the Redis caching implementation in the Echno Backend project. It's designed to help you understand not just *what* was implemented, but *why* and *how* — so you can build similar or more complex caching systems on your own.

---

## Table of Contents

1. [Understanding Caching Fundamentals](#1-understanding-caching-fundamentals)
2. [Why Redis?](#2-why-redis)
3. [Spring Cache Abstraction](#3-spring-cache-abstraction)
4. [Project Architecture Overview](#4-project-architecture-overview)
5. [Configuration Deep Dive](#5-configuration-deep-dive)
6. [Implementing Caching in Services](#6-implementing-caching-in-services)
7. [Cache Eviction Strategies](#7-cache-eviction-strategies)
8. [Serialization Explained](#8-serialization-explained)
9. [Common Pitfalls and How to Avoid Them](#9-common-pitfalls-and-how-to-avoid-them)
10. [Testing Your Cache](#10-testing-your-cache)
11. [Monitoring and Debugging](#11-monitoring-and-debugging)
12. [Advanced Patterns](#12-advanced-patterns)
13. [Scaling Considerations](#13-scaling-considerations)
14. [Quick Reference](#14-quick-reference)

---

## 1. Understanding Caching Fundamentals

### What is Caching?

Caching is the practice of storing frequently accessed data in a fast-access storage layer (the "cache") to reduce the time and resources needed to fetch that data from its original source (like a database).

```
Without Cache:
┌────────┐     ┌────────────┐     ┌──────────┐
│ Client │ ──► │ Application│ ──► │ Database │  (Every request hits DB)
└────────┘     └────────────┘     └──────────┘
                                       │
                                   50-100ms

With Cache:
┌────────┐     ┌────────────┐     ┌───────┐
│ Client │ ──► │ Application│ ──► │ Redis │  (Cache Hit: 1-5ms)
└────────┘     └────────────┘     └───────┘
                     │                 │
                     │            Cache Miss
                     │                 ▼
                     │           ┌──────────┐
                     └─────────► │ Database │  (Only on cache miss)
                                 └──────────┘
```

### The Cache Hit/Miss Concept

- **Cache Hit**: Data is found in the cache → Return immediately (fast!)
- **Cache Miss**: Data is NOT in the cache → Fetch from database, store in cache, return

### When to Use Caching

Use caching when:
- Data is read frequently but written infrequently
- Computing/fetching the data is expensive (complex queries, external APIs)
- Slightly stale data is acceptable
- You have predictable access patterns

Don't use caching when:
- Data changes very frequently
- Data must always be 100% fresh (real-time financial transactions)
- The data is unique per request (won't benefit from caching)
- Memory is extremely limited

### Cache-Aside Pattern (What We Implemented)

This is the most common caching pattern:

```
READ Operation:
1. Check if data exists in cache
2. If YES (cache hit): return cached data
3. If NO (cache miss):
   a. Fetch from database
   b. Store in cache
   c. Return data

WRITE Operation:
1. Write to database
2. Invalidate (delete) the cache entry
3. Next read will populate cache with fresh data
```

---

## 2. Why Redis?

### What is Redis?

Redis (Remote Dictionary Server) is an in-memory data store that can be used as:
- Cache
- Database
- Message broker
- Session store

### Why Choose Redis Over Other Caching Solutions?

| Feature | Redis | Caffeine (In-Memory) | Memcached |
|---------|-------|---------------------|-----------|
| Persistence | Yes | No | No |
| Data Structures | Rich (lists, sets, hashes) | Key-Value only | Key-Value only |
| Distributed | Yes | No (per-JVM) | Yes |
| Clustering | Yes | No | Limited |
| Pub/Sub | Yes | No | No |
| Lua Scripting | Yes | No | No |

### When to Use Redis vs In-Memory Cache

**Use In-Memory Cache (Caffeine) when:**
- Single application instance
- Data doesn't need to be shared across instances
- Simpler setup is preferred
- Ultra-low latency required (sub-millisecond)

**Use Redis when:**
- Multiple application instances (horizontal scaling)
- Cache needs to survive application restarts
- Cache needs to be shared across services
- You need advanced data structures
- You want centralized cache management

### Redis in Our Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Load Balancer                             │
└─────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
   │   App #1    │     │   App #2    │     │   App #3    │
   └─────────────┘     └─────────────┘     └─────────────┘
          │                   │                   │
          └───────────────────┼───────────────────┘
                              ▼
                    ┌─────────────────┐
                    │     Redis       │  ◄── Shared Cache
                    └─────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │   PostgreSQL    │
                    └─────────────────┘
```

All application instances share the same Redis cache, ensuring consistency.

---

## 3. Spring Cache Abstraction

### What is Spring Cache Abstraction?

Spring provides a caching abstraction that allows you to add caching to your application with minimal code changes. It's provider-agnostic, meaning you can switch from one cache provider (like Caffeine) to another (like Redis) without changing your service code.

### Key Annotations

#### `@EnableCaching`

Enables Spring's caching infrastructure. Add this to your main application class or a configuration class.

```java
@SpringBootApplication
@EnableCaching  // This enables caching support
public class EchnoBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(EchnoBackendApplication.class, args);
    }
}
```

#### `@Cacheable`

Caches the result of a method. If the method is called again with the same parameters, the cached result is returned instead of executing the method.

```java
@Cacheable(value = "users", key = "#userId")
public UserDto getUser(Long userId) {
    // This method body only executes on cache miss
    return userRepository.findById(userId)
            .map(this::convertToDto)
            .orElseThrow();
}
```

**How it works:**
1. Before method execution, Spring checks if a cached value exists for the key
2. If found (cache hit): return cached value, method body is NOT executed
3. If not found (cache miss): execute method, cache the result, return it

**Parameters explained:**
- `value` (or `cacheNames`): The name of the cache to use
- `key`: SpEL expression to compute the cache key (default: all parameters)
- `condition`: SpEL expression; cache only if this evaluates to true
- `unless`: SpEL expression; don't cache if this evaluates to true

#### `@CacheEvict`

Removes entries from the cache. Use this when data is modified.

```java
@CacheEvict(value = "users", key = "#userId")
public void deleteUser(Long userId) {
    userRepository.deleteById(userId);
    // Cache entry for this userId is automatically removed
}
```

**Parameters explained:**
- `value`: Cache name(s) to evict from
- `key`: Specific key to evict
- `allEntries`: If true, clears the entire cache (use carefully!)
- `beforeInvocation`: If true, evict before method executes (default: after)

#### `@CachePut`

Always executes the method and updates the cache with the result. Use this for update operations.

```java
@CachePut(value = "users", key = "#userId")
public UserDto updateUser(Long userId, UserUpdateDto updateDto) {
    User user = userRepository.findById(userId).orElseThrow();
    user.setName(updateDto.getName());
    return convertToDto(userRepository.save(user));
    // Method always executes, cache is updated with new value
}
```

**Difference from @Cacheable:**
- `@Cacheable`: Skips method execution if cache hit
- `@CachePut`: Always executes method, updates cache with result

#### `@Caching`

Combines multiple cache operations on a single method.

```java
@Caching(evict = {
    @CacheEvict(value = "user", key = "#id"),
    @CacheEvict(value = "user-list", allEntries = true),
    @CacheEvict(value = "user-organizations", key = "#id")
})
public void deleteUser(Long id) {
    userRepository.deleteById(id);
}
```

### SpEL (Spring Expression Language) for Cache Keys

SpEL is used to define cache keys dynamically:

```java
// Use method parameter
@Cacheable(value = "users", key = "#userId")
public UserDto getUser(Long userId) { }

// Use object property
@Cacheable(value = "users", key = "#user.id")
public UserDto saveUser(User user) { }

// Use multiple parameters
@Cacheable(value = "search", key = "#name + '-' + #page")
public List<UserDto> search(String name, int page) { }

// Use static value
@Cacheable(value = "config", key = "'app-settings'")
public ConfigDto getConfig() { }

// Conditional caching
@Cacheable(value = "users", key = "#id", condition = "#id > 0")
public UserDto getUser(Long id) { }

// Don't cache null results
@Cacheable(value = "users", key = "#id", unless = "#result == null")
public UserDto getUser(Long id) { }
```

---

## 4. Project Architecture Overview

### Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Controller Layer                        │
│                   (REST API Endpoints)                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                       Service Layer                          │
│              (Business Logic + CACHING HERE)                 │
│                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │ @Cacheable  │  │ @CacheEvict │  │ @CachePut   │          │
│  └─────────────┘  └─────────────┘  └─────────────┘          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Repository Layer                         │
│                    (Data Access - JPA)                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                        Database                              │
└─────────────────────────────────────────────────────────────┘
```

### Why Cache at the Service Layer?

1. **Business logic is encapsulated**: Services know what data means and how it relates
2. **Controller remains thin**: Controllers just handle HTTP concerns
3. **Repository stays simple**: Repositories just do data access
4. **Testability**: Easy to mock services for testing
5. **Consistency**: Single point for cache management per domain

### Our Caching Components

```
src/main/java/org/tornotron/echno_backend/
├── common/
│   ├── configuration/
│   │   └── RedisCacheConfig.java      # Cache configuration
│   └── events/
│       └── listeners/
│           └── InventoryEventListener.java  # Event-driven cache eviction
├── inventoryTransaction/
│   └── InventoryService.java          # Stock caching
├── material/
│   └── MaterialService.java           # Material caching
├── organization/
│   └── OrganizationService.java       # Organization caching
├── employee/
│   └── EmployeeService.java           # Employee caching
├── user/
│   └── UserService.java               # User caching
└── project/
    └── ProjectService.java            # Project caching
```

---

## 5. Configuration Deep Dive

### Understanding RedisCacheConfig.java

Let's break down the configuration file piece by piece:

```java
@Configuration
public class RedisCacheConfig implements CachingConfigurer {
```

**What this does:**
- `@Configuration`: Marks this as a Spring configuration class
- `CachingConfigurer`: Interface that allows customizing cache behavior (error handling, key generation, etc.)

### Creating the ObjectMapper for Redis

```java
private ObjectMapper createRedisObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();

    // 1. Support for Java 8 date/time types
    mapper.registerModule(new JavaTimeModule());

    // 2. Human-readable dates instead of timestamps
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // 3. Don't fail if cached data has extra fields
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // 4. Include type information for proper deserialization
    mapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
    );

    return mapper;
}
```

**Why each setting matters:**

1. **JavaTimeModule**: Without this, `LocalDateTime`, `LocalDate`, etc. won't serialize properly
   ```java
   // Without JavaTimeModule:
   {"createdAt": [2024, 1, 15, 10, 30, 0]}  // Array - hard to read

   // With JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS disabled:
   {"createdAt": "2024-01-15T10:30:00"}  // ISO format - human readable
   ```

2. **FAIL_ON_UNKNOWN_PROPERTIES disabled**: If your DTO changes (add/remove fields), old cached data won't cause errors. This is crucial for deployments!

3. **Default Typing**: This is the most important and tricky part. Let me explain:

   ```java
   // Without type information:
   {"id": 1, "name": "John"}

   // With type information:
   {"@class": "org.example.UserDto", "id": 1, "name": "John"}
   ```

   **Why is this needed?**

   When you cache a `List<UserDto>`, Java's type erasure means Redis just sees a `List`. Without type info, Redis doesn't know what class to deserialize the list items into.

### Default Cache Configuration

```java
private RedisCacheConfiguration defaultCacheConfiguration() {
    GenericJackson2JsonRedisSerializer jsonSerializer =
            new GenericJackson2JsonRedisSerializer(createRedisObjectMapper());

    return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))           // Default TTL
            .disableCachingNullValues()                  // Don't cache nulls
            .serializeKeysWith(                          // String keys
                    RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer())
            )
            .serializeValuesWith(                        // JSON values
                    RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer)
            );
}
```

**Configuration breakdown:**

| Setting | Value | Why |
|---------|-------|-----|
| `entryTtl` | 30 minutes | Default expiration; prevents stale data forever |
| `disableCachingNullValues` | true | Prevents cache pollution with nulls |
| `serializeKeysWith` | StringRedisSerializer | Human-readable keys in Redis |
| `serializeValuesWith` | JSON serializer | Structured, debuggable values |

### Cache-Specific TTL Configuration

```java
@Bean
@Primary
public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
    Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
    RedisCacheConfiguration defaultConfig = defaultCacheConfiguration();

    // Short TTL for frequently changing data
    cacheConfigurations.put("inventory-stock",
            defaultConfig.entryTtl(Duration.ofMinutes(5)));

    // Medium TTL for moderately changing data
    cacheConfigurations.put("employee",
            defaultConfig.entryTtl(Duration.ofMinutes(15)));

    // Longer TTL for stable reference data
    cacheConfigurations.put("project-organization",
            defaultConfig.entryTtl(Duration.ofMinutes(60)));

    return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .transactionAware()  // Respect @Transactional boundaries
            .build();
}
```

**TTL Strategy Rationale:**

| Cache | TTL | Reasoning |
|-------|-----|-----------|
| `inventory-stock` | 5 min | Stock changes with every GRN/consumption; needs freshness |
| `employee` | 15 min | Employee data changes occasionally |
| `organization` | 30 min | Relatively stable reference data |
| `project-organization` | 60 min | Almost never changes; mapping is stable |

### Why `transactionAware()`?

```java
.transactionAware()
```

This ensures cache operations respect `@Transactional` boundaries:

```java
@Transactional
public void transferStock(Long fromId, Long toId, int quantity) {
    // These cache operations only commit if transaction succeeds
    decreaseStock(fromId, quantity);  // Updates cache
    increaseStock(toId, quantity);    // Updates cache

    // If an exception occurs here, cache changes are rolled back!
    validateTransfer();
}
```

Without `transactionAware()`, cache might be updated even if the database transaction fails, causing inconsistency.

### Application Configuration (application.yml)

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}      # Environment variable with default
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}        # Empty default (no password)
      timeout: 2000ms                     # Connection timeout
      lettuce:
        pool:
          max-active: 8                   # Max concurrent connections
          max-idle: 8                     # Max idle connections
          min-idle: 2                     # Min idle connections
          max-wait: -1ms                  # Wait indefinitely for connection
  cache:
    type: redis                           # Use Redis as cache provider
    cache-names:                          # Pre-define cache names
      - inventory-stock
      - material
      - organization
      # ... etc
```

**Connection Pool Explained:**

```
Connection Pool (max-active: 8)
┌─────────────────────────────────────────────────┐
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐                    │
│  │Conn│ │Conn│ │Conn│ │Conn│  ... up to 8       │
│  │ #1 │ │ #2 │ │ #3 │ │ #4 │                    │
│  └────┘ └────┘ └────┘ └────┘                    │
│     │      │      │      │                       │
│     ▼      ▼      ▼      ▼                       │
│  ┌───────────────────────────────────────────┐  │
│  │           Redis Server                     │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘

Without pooling: Create new connection for each request (slow!)
With pooling: Reuse existing connections (fast!)
```

---

## 6. Implementing Caching in Services

### Pattern 1: Simple Read Caching

The most common pattern - cache read operations:

```java
/**
 * Retrieves a material by ID.
 *
 * Caching Strategy:
 * - Cache name: "material"
 * - Cache key: material ID
 * - TTL: 30 minutes
 * - Eviction: Triggered on update or delete
 */
@Transactional(readOnly = true)
@Cacheable(value = "material", key = "#id")
public MaterialDto getMaterialById(Long id) {
    // This only executes on cache miss
    Material material = materialRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Material not found"));
    return MaterialDtoConvertor.convertToDto(material);
}
```

**Flow diagram:**

```
getMaterialById(123)
        │
        ▼
┌───────────────────┐
│ Check Redis cache │
│ Key: "material::123" │
└───────────────────┘
        │
   ┌────┴────┐
   │         │
Cache Hit  Cache Miss
   │         │
   ▼         ▼
Return    Query DB
cached    Store in Redis
value     Return value
```

### Pattern 2: List Caching

Caching a collection with a fixed key:

```java
@Transactional(readOnly = true)
@Cacheable(value = "materials-list", key = "'all'")  // Note: literal string key
public List<MaterialDto> getAllMaterials() {
    return materialRepository.findAll().stream()
            .map(MaterialDtoConvertor::convertToDto)
            .collect(Collectors.toList());
}
```

**Why use a literal key `'all'`?**

Without parameters, Spring would use an empty key, which can be problematic. Using `'all'` makes it explicit and readable in Redis:
- Key in Redis: `materials-list::all`

### Pattern 3: Parameterized List Caching

Caching lists based on a parameter:

```java
@Transactional(readOnly = true)
@Cacheable(value = "employees-by-organization", key = "#organizationId")
public List<EmployeeDto> displayEmployeesByOrganization(Long organizationId) {
    return employeeRepository.findEmployeesByOrganization_Id(organizationId)
            .stream()
            .map(EmployeeDtoConvertor::convertEmployeeToDto)
            .collect(Collectors.toList());
}
```

**Keys in Redis:**
- `employees-by-organization::1` (for org ID 1)
- `employees-by-organization::2` (for org ID 2)
- etc.

### Pattern 4: Create with Cache Eviction

When creating new data, invalidate related list caches:

```java
@Transactional
@CacheEvict(value = "materials-list", allEntries = true)
public MaterialDto createMaterial(MaterialCreationDto creationDto) {
    // Validate
    if (creationDto.getSku() != null && materialRepository.existsBySku(creationDto.getSku())) {
        throw new DuplicateResourceException("SKU already exists");
    }

    // Create and save
    Material material = new Material();
    material.setSku(creationDto.getSku());
    material.setMaterialName(creationDto.getMaterialName());
    material.setUnit(creationDto.getUnit());

    material = materialRepository.save(material);
    return MaterialDtoConvertor.convertToDto(material);

    // After this method, "materials-list" cache is cleared
}
```

### Pattern 5: Update with Multiple Evictions

When updating, evict both the specific item and related lists:

```java
@Transactional
@Caching(evict = {
    @CacheEvict(value = "material", key = "#id"),           // Specific item
    @CacheEvict(value = "materials-list", allEntries = true) // All lists
})
public MaterialDto updateMaterial(Long id, MaterialCreationDto updateDto) {
    Material material = materialRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Not found"));

    material.setMaterialName(updateDto.getMaterialName());
    material.setUnit(updateDto.getUnit());

    material = materialRepository.save(material);
    return MaterialDtoConvertor.convertToDto(material);
}
```

### Pattern 6: Delete with Cascade Eviction

When deleting, consider all related caches:

```java
@Transactional
@Caching(evict = {
    @CacheEvict(value = "organization", key = "#id"),
    @CacheEvict(value = "organizations-by-creator", allEntries = true),
    @CacheEvict(value = "employees-by-organization", key = "#id")
})
public void deleteAnOrganization(Long id) {
    if (!repository.existsById(id)) {
        throw new ResourceNotFoundException("Not found");
    }
    repository.deleteById(id);
}
```

### Pattern 7: Conditional Eviction Based on Relationships

When an action affects multiple related caches:

```java
@Transactional
@Caching(evict = {
    @CacheEvict(value = "employees-by-organization", key = "#orgId"),
    @CacheEvict(value = "user-organizations", key = "#userId")
})
public EmployeeDto joinOrganization(Long userId, Long orgId, EmployeeJoinOrgDto dto) {
    // When user joins org:
    // 1. The org's employee list changes -> evict employees-by-organization
    // 2. The user's org list changes -> evict user-organizations

    User user = userRepository.findById(userId).orElseThrow();
    Organization org = organizationRepository.findById(orgId).orElseThrow();

    Employee employee = new Employee();
    employee.setUser(user);
    employee.setOrganization(org);
    // ... set other fields

    return EmployeeDtoConvertor.convertEmployeeToDto(
        employeeRepository.save(employee)
    );
}
```

---

## 7. Cache Eviction Strategies

### Strategy 1: Direct Annotation-Based Eviction

The simplest approach - add `@CacheEvict` to write methods:

```java
// On the same service
@CacheEvict(value = "material", key = "#id")
public void deleteMaterial(Long id) {
    materialRepository.deleteById(id);
}
```

**Pros:** Simple, co-located with business logic
**Cons:** Limited to the same service

### Strategy 2: Event-Driven Eviction

For cross-service cache invalidation, use Spring Events:

```java
// Event class
public class InventoryChangedEvent {
    private final Long materialId;

    public InventoryChangedEvent(Long materialId) {
        this.materialId = materialId;
    }

    public Long getMaterialId() {
        return materialId;
    }
}

// Service that publishes events
@Service
public class GoodsReceivedNoteService {

    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public GrnDto createGrn(GrnCreationDto dto) {
        // Save GRN to database
        GoodsReceivedNote grn = saveGrn(dto);

        // Publish event for each material affected
        for (GrnItem item : grn.getItems()) {
            eventPublisher.publishEvent(
                new InventoryChangedEvent(item.getMaterial().getId())
            );
        }

        return convertToDto(grn);
    }
}

// Event listener that evicts cache
@Component
public class InventoryEventListener {

    private final InventoryService inventoryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleInventoryChanged(InventoryChangedEvent event) {
        // Evict cache only after transaction commits successfully
        inventoryService.evictStockCache(event.getMaterialId());
    }
}

// Service with eviction method
@Service
public class InventoryService {

    @Cacheable(value = "inventory-stock", key = "#materialId")
    public Integer getCurrentStock(Long materialId) {
        // ... fetch from DB
    }

    @CacheEvict(value = "inventory-stock", key = "#materialId")
    public void evictStockCache(Long materialId) {
        // Empty method - just for cache eviction
    }
}
```

**Why use `TransactionPhase.AFTER_COMMIT`?**

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
```

This ensures cache is only evicted after the database transaction successfully commits:

```
Timeline:
─────────────────────────────────────────────────────►
     │                  │                    │
  Start TX          Exception?          Commit TX
     │                  │                    │
     ▼                  ▼                    ▼
 Save data         Rollback!           Evict cache
     │                  │                    │
     └──────────────────┘                    │
        Cache NOT evicted             Cache evicted
        (data not saved)              (data is saved)
```

### Strategy 3: Time-Based Eviction (TTL)

Let the cache naturally expire:

```java
cacheConfigurations.put("inventory-stock",
        defaultConfig.entryTtl(Duration.ofMinutes(5)));
```

**When to rely on TTL:**
- When eventual consistency is acceptable
- When explicit eviction is complex
- As a safety net in addition to explicit eviction

### Strategy 4: Programmatic Eviction

For complex scenarios, evict programmatically:

```java
@Service
public class CacheManagementService {

    private final CacheManager cacheManager;

    // Evict specific key
    public void evictFromCache(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    // Clear entire cache
    public void clearCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    // Evict multiple keys
    public void evictMultiple(String cacheName, Collection<?> keys) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            keys.forEach(cache::evict);
        }
    }
}
```

---

## 8. Serialization Explained

### Why Serialization Matters

Redis stores data as bytes. To store Java objects, we need to:
1. **Serialize**: Convert Java object → bytes (when storing)
2. **Deserialize**: Convert bytes → Java object (when retrieving)

### Serialization Options

#### 1. JDK Serialization (Default - NOT Recommended)

```java
// Objects must implement Serializable
public class UserDto implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private String name;
}
```

**Problems:**
- Not human-readable in Redis
- Fragile: class changes break deserialization
- Java-specific: can't share with other languages

#### 2. JSON Serialization (Recommended)

```java
GenericJackson2JsonRedisSerializer jsonSerializer =
    new GenericJackson2JsonRedisSerializer(objectMapper);
```

**Data in Redis:**
```json
{
  "@class": "org.example.UserDto",
  "id": 1,
  "name": "John Doe",
  "createdAt": "2024-01-15T10:30:00"
}
```

**Advantages:**
- Human-readable (great for debugging)
- Language-agnostic
- More tolerant of class changes

### Common Serialization Issues and Solutions

#### Issue 1: LocalDateTime Serialization

```java
// Problem: Without JavaTimeModule
{"createdAt": {"year": 2024, "month": "JANUARY", ...}}  // Ugly!

// Solution: Register JavaTimeModule
mapper.registerModule(new JavaTimeModule());
mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

// Result:
{"createdAt": "2024-01-15T10:30:00"}  // Clean ISO format
```

#### Issue 2: Polymorphic Types

```java
// Problem: Caching List<? extends BaseDto>
List<BaseDto> items = getItems();  // Contains UserDto, AdminDto, etc.

// Without type info, deserializer doesn't know actual types
// Solution: Enable default typing
mapper.activateDefaultTyping(
    LaissezFaireSubTypeValidator.instance,
    ObjectMapper.DefaultTyping.NON_FINAL,
    JsonTypeInfo.As.PROPERTY
);

// Each object now includes its class:
[
  {"@class": "UserDto", "id": 1, "name": "John"},
  {"@class": "AdminDto", "id": 2, "name": "Jane", "permissions": [...]}
]
```

#### Issue 3: Lazy-Loaded JPA Entities

```java
// NEVER cache JPA entities directly!
@Cacheable("users")
public User getUser(Long id) {  // BAD - Returns entity
    return userRepository.findById(id).orElseThrow();
}
```

**Problems:**
- Lazy-loaded associations fail outside transaction
- Hibernate proxies don't serialize well
- Large object graphs get cached

**Solution: Use DTOs**

```java
@Cacheable("users")
public UserDto getUser(Long id) {  // GOOD - Returns DTO
    User user = userRepository.findById(id).orElseThrow();
    return convertToDto(user);  // Only cache the DTO
}
```

#### Issue 4: Class Not Found After Deployment

```java
// Problem: Cached data has old class structure
// After deployment, class moved/renamed
{"@class": "org.example.old.UserDto", ...}  // Class doesn't exist anymore!

// Solutions:
// 1. Clear cache on deployment
// 2. Use TTL so old data expires
// 3. Use FAIL_ON_UNKNOWN_PROPERTIES = false for field changes
```

---

## 9. Common Pitfalls and How to Avoid Them

### Pitfall 1: Caching Mutable Objects

```java
// BAD: Caching mutable object
@Cacheable("users")
public User getUser(Long id) {
    return userRepository.findById(id).orElseThrow();
}

// Somewhere else...
User user = userService.getUser(1L);
user.setName("Modified");  // This modifies the cached object!
```

**Solution: Use immutable DTOs**

```java
// GOOD: Return immutable DTO
@Cacheable("users")
public UserDto getUser(Long id) {
    User user = userRepository.findById(id).orElseThrow();
    return new UserDto(user);  // Create new DTO each time
}
```

### Pitfall 2: Cache Aside with Race Condition

```java
// Potential race condition:
// Thread 1: Cache miss, fetches from DB (value = 100)
// Thread 2: Updates DB (value = 200), evicts cache
// Thread 1: Stores old value (100) in cache  <-- STALE!

// Solution: Use TTL as safety net
// Even if race condition occurs, stale data expires
```

### Pitfall 3: Caching Paginated Results

```java
// BAD: Different pages overwrite each other
@Cacheable("users")
public Page<UserDto> getUsers(Pageable pageable) {
    return userRepository.findAll(pageable).map(this::toDto);
}
// All page requests use same cache key!

// GOOD: Include page info in cache key
@Cacheable(value = "users", key = "'page:' + #pageable.pageNumber + ':' + #pageable.pageSize")
public Page<UserDto> getUsers(Pageable pageable) {
    return userRepository.findAll(pageable).map(this::toDto);
}

// Or: Don't cache paginated results at all
// Pagination is typically for browsing, not repeated access
```

### Pitfall 4: Forgetting to Evict Related Caches

```java
// BAD: Only evicts single item
@CacheEvict(value = "user", key = "#id")
public void deleteUser(Long id) {
    userRepository.deleteById(id);
}
// What about user-list cache? Still contains deleted user!

// GOOD: Evict all related caches
@Caching(evict = {
    @CacheEvict(value = "user", key = "#id"),
    @CacheEvict(value = "user-list", allEntries = true),
    @CacheEvict(value = "user-organizations", key = "#id")
})
public void deleteUser(Long id) {
    userRepository.deleteById(id);
}
```

### Pitfall 5: Caching Methods with Side Effects

```java
// BAD: Method has side effects
@Cacheable("reports")
public ReportDto generateReport(Long userId) {
    reportRepository.logAccess(userId);  // Side effect!
    return createReport(userId);
}
// On cache hit, logAccess() is NOT called!

// GOOD: Separate caching from side effects
public ReportDto generateReport(Long userId) {
    reportRepository.logAccess(userId);  // Always called
    return getCachedReport(userId);       // Cached part
}

@Cacheable("reports")
public ReportDto getCachedReport(Long userId) {
    return createReport(userId);  // No side effects
}
```

### Pitfall 6: Self-Invocation Bypass

```java
@Service
public class UserService {

    @Cacheable("users")
    public UserDto getUser(Long id) {
        return fetchUser(id);
    }

    public UserDto getUserWithExtras(Long id) {
        UserDto user = getUser(id);  // CACHE IS BYPASSED!
        // Because it's a self-invocation (this.getUser())
        return addExtras(user);
    }
}
```

**Why this happens:**

Spring caching uses AOP proxies. When you call a method on `this`, you bypass the proxy:

```
External call:         Self-invocation:
    │                        │
    ▼                        ▼
┌────────┐              ┌────────┐
│ Proxy  │              │ Proxy  │  (Bypassed!)
│ (AOP)  │              │        │
└───┬────┘              └────────┘
    │                        │
    ▼                        ▼
┌────────┐              ┌────────┐
│ Target │              │ Target │
│ Bean   │              │ Bean   │
└────────┘              └────────┘
```

**Solutions:**

```java
// Solution 1: Inject self
@Service
public class UserService {
    @Lazy
    @Autowired
    private UserService self;  // Inject proxy

    public UserDto getUserWithExtras(Long id) {
        UserDto user = self.getUser(id);  // Goes through proxy
        return addExtras(user);
    }
}

// Solution 2: Separate services
@Service
public class UserCacheService {
    @Cacheable("users")
    public UserDto getUser(Long id) { ... }
}

@Service
public class UserService {
    private final UserCacheService cacheService;

    public UserDto getUserWithExtras(Long id) {
        UserDto user = cacheService.getUser(id);  // Different bean
        return addExtras(user);
    }
}
```

### Pitfall 7: Not Handling Cache Failures

```java
// If Redis is down, your app crashes!

// Solution: Use CacheErrorHandler
@Override
public CacheErrorHandler errorHandler() {
    return new SimpleCacheErrorHandler();
    // Logs errors but doesn't throw exceptions
    // App continues working without cache
}
```

---

## 10. Testing Your Cache

### Unit Testing with MockBean

```java
@SpringBootTest
class UserServiceTest {

    @Autowired
    private UserService userService;

    @MockBean
    private UserRepository userRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("users").clear();
    }

    @Test
    void shouldCacheUserOnFirstCall() {
        // Given
        User user = new User(1L, "John");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // When
        userService.getUser(1L);  // First call - cache miss
        userService.getUser(1L);  // Second call - should be cache hit

        // Then
        verify(userRepository, times(1)).findById(1L);  // Only called once!
    }

    @Test
    void shouldEvictCacheOnUpdate() {
        // Given
        User user = new User(1L, "John");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // Cache the user
        userService.getUser(1L);

        // When - update
        userService.updateUser(1L, new UserUpdateDto("Jane"));

        // Then - cache should be evicted
        userService.getUser(1L);  // Should hit DB again
        verify(userRepository, times(2)).findById(1L);
    }
}
```

### Integration Testing with Embedded Redis

```java
// Add to test dependencies:
// testImplementation 'it.ozimov:embedded-redis:0.7.3'

@SpringBootTest
@TestPropertySource(properties = {
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6370"
})
class CacheIntegrationTest {

    private static redis.embedded.RedisServer redisServer;

    @BeforeAll
    static void startRedis() {
        redisServer = new redis.embedded.RedisServer(6370);
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() {
        redisServer.stop();
    }

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void shouldStoreInRedis() {
        // Given
        userService.getUser(1L);

        // Then
        Set<String> keys = redisTemplate.keys("users::*");
        assertThat(keys).contains("users::1");
    }
}
```

### Verifying Cache Behavior

```java
@Test
void verifyCacheKeyFormat() {
    // Call cached method
    userService.getUser(123L);

    // Check Redis directly
    Cache cache = cacheManager.getCache("users");
    Cache.ValueWrapper wrapper = cache.get(123L);

    assertNotNull(wrapper);
    assertThat(wrapper.get()).isInstanceOf(UserDto.class);
}
```

---

## 11. Monitoring and Debugging

### Enabling Cache Logging

```yaml
# application.yml
logging:
  level:
    org.springframework.cache: DEBUG
    org.springframework.data.redis: DEBUG
```

**Sample log output:**

```
DEBUG - Cache entry for key '123' found in cache 'users'
DEBUG - No cache entry for key '456' in cache 'users'
DEBUG - Creating cache entry for key '456' in cache 'users'
```

### Redis CLI Commands for Debugging

```bash
# Connect to Redis
redis-cli

# View all keys
KEYS *

# View keys matching pattern
KEYS users::*

# Get a cached value (as string)
GET users::123

# View TTL remaining
TTL users::123

# Delete a key
DEL users::123

# Clear all keys
FLUSHALL

# Monitor all commands in real-time
MONITOR
```

### Spring Actuator Metrics

Add to your dependencies:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

Enable cache metrics:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: caches, prometheus
  metrics:
    cache:
      instrument: true
```

Access metrics:
- `GET /actuator/caches` - List all caches
- `GET /actuator/caches/{cacheName}` - Specific cache info
- `DELETE /actuator/caches/{cacheName}` - Clear a cache

### Prometheus Metrics

With Micrometer, you get metrics like:
- `cache_gets_total{cache="users",result="hit"}` - Cache hits
- `cache_gets_total{cache="users",result="miss"}` - Cache misses
- `cache_puts_total{cache="users"}` - Cache puts
- `cache_evictions_total{cache="users"}` - Cache evictions

Calculate hit ratio:
```
cache_gets_total{result="hit"} / (cache_gets_total{result="hit"} + cache_gets_total{result="miss"})
```

### Custom Cache Inspector Service

```java
@Service
public class CacheInspectorService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheManager cacheManager;

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();

        for (String cacheName : cacheManager.getCacheNames()) {
            Set<String> keys = redisTemplate.keys(cacheName + "::*");
            stats.put(cacheName, Map.of(
                "keyCount", keys != null ? keys.size() : 0,
                "keys", keys != null ? keys : Set.of()
            ));
        }

        return stats;
    }

    public Object getCacheEntry(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) return null;

        Cache.ValueWrapper wrapper = cache.get(key);
        return wrapper != null ? wrapper.get() : null;
    }
}
```

---

## 12. Advanced Patterns

### Pattern 1: Two-Level Caching (L1 + L2)

Combine local cache (Caffeine) with distributed cache (Redis):

```
Request → L1 (Caffeine) → L2 (Redis) → Database
              1ms            5ms          50ms
```

```java
@Configuration
public class TwoLevelCacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory redis) {
        // L1: Caffeine (local)
        CaffeineCacheManager caffeineManager = new CaffeineCacheManager();
        caffeineManager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(5)));

        // L2: Redis (distributed)
        RedisCacheManager redisManager = RedisCacheManager.builder(redis)
            .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30)))
            .build();

        // Composite manager
        return new CompositeCacheManager(caffeineManager, redisManager);
    }
}
```

### Pattern 2: Cache-Aside with Write-Through

Ensure cache is always updated on writes:

```java
@Service
public class UserService {

    @CachePut(value = "users", key = "#result.id")  // Update cache with result
    public UserDto createUser(UserCreationDto dto) {
        User user = new User();
        user.setName(dto.getName());
        user = userRepository.save(user);
        return convertToDto(user);
    }

    @CachePut(value = "users", key = "#id")
    public UserDto updateUser(Long id, UserUpdateDto dto) {
        User user = userRepository.findById(id).orElseThrow();
        user.setName(dto.getName());
        return convertToDto(userRepository.save(user));
    }
}
```

### Pattern 3: Refresh-Ahead

Proactively refresh cache before it expires:

```java
@Component
public class CacheRefreshScheduler {

    private final UserService userService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Scheduled(fixedRate = 60000)  // Every minute
    public void refreshHotKeys() {
        // Get keys expiring soon
        Set<String> keys = redisTemplate.keys("users::*");

        for (String key : keys) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

            // If TTL < 5 minutes, refresh
            if (ttl != null && ttl < 300) {
                Long userId = extractUserId(key);
                userService.refreshUser(userId);  // Re-fetches and caches
            }
        }
    }
}
```

### Pattern 4: Request-Scoped Caching

For data that should be cached only within a single request:

```java
@Component
@RequestScope  // New instance per request
public class RequestCache {
    private final Map<String, Object> cache = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(String key, Supplier<T> supplier) {
        return (T) cache.computeIfAbsent(key, k -> supplier.get());
    }
}

@Service
public class UserService {
    private final RequestCache requestCache;

    public UserDto getUser(Long id) {
        return requestCache.getOrCompute(
            "user:" + id,
            () -> fetchFromDatabase(id)
        );
    }
}
```

### Pattern 5: Batch Cache Loading

Load multiple items efficiently:

```java
@Service
public class ProductService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductRepository repository;

    public List<ProductDto> getProducts(List<Long> ids) {
        // 1. Build cache keys
        List<String> cacheKeys = ids.stream()
            .map(id -> "products::" + id)
            .toList();

        // 2. Multi-get from Redis
        List<Object> cachedValues = redisTemplate.opsForValue()
            .multiGet(cacheKeys);

        // 3. Find missing IDs
        List<Long> missingIds = new ArrayList<>();
        Map<Long, ProductDto> results = new HashMap<>();

        for (int i = 0; i < ids.size(); i++) {
            if (cachedValues.get(i) != null) {
                results.put(ids.get(i), (ProductDto) cachedValues.get(i));
            } else {
                missingIds.add(ids.get(i));
            }
        }

        // 4. Fetch missing from DB and cache
        if (!missingIds.isEmpty()) {
            List<Product> products = repository.findAllById(missingIds);
            Map<String, ProductDto> toCache = new HashMap<>();

            for (Product p : products) {
                ProductDto dto = convertToDto(p);
                results.put(p.getId(), dto);
                toCache.put("products::" + p.getId(), dto);
            }

            // Multi-set to Redis
            redisTemplate.opsForValue().multiSet(toCache);
        }

        // 5. Return in original order
        return ids.stream()
            .map(results::get)
            .filter(Objects::nonNull)
            .toList();
    }
}
```

---

## 13. Scaling Considerations

### Redis Cluster Mode

For high availability and horizontal scaling:

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - redis-node-1:6379
          - redis-node-2:6379
          - redis-node-3:6379
        max-redirects: 3
      lettuce:
        cluster:
          refresh:
            adaptive: true
            period: 30000
```

### Redis Sentinel (High Availability)

For automatic failover:

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - sentinel-1:26379
          - sentinel-2:26379
          - sentinel-3:26379
```

### Cache Stampede Prevention

When a popular cache entry expires, many requests hit the database simultaneously:

```
Cache expires at T=0
T=0.001: Request 1 → Cache Miss → DB
T=0.002: Request 2 → Cache Miss → DB
T=0.003: Request 3 → Cache Miss → DB
... hundreds of requests hit DB!
```

**Solution: Probabilistic Early Expiration**

```java
@Component
public class SmartCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final Random random = new Random();

    public Object getWithProbabilisticRefresh(
            String key,
            Supplier<Object> fetcher,
            Duration ttl,
            double beta) {  // beta = 1.0 is a good default

        Object value = redisTemplate.opsForValue().get(key);
        Long remainingTtl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

        if (value == null || shouldRefresh(remainingTtl, ttl.toMillis(), beta)) {
            value = fetcher.get();
            redisTemplate.opsForValue().set(key, value, ttl);
        }

        return value;
    }

    private boolean shouldRefresh(Long remainingTtl, long originalTtl, double beta) {
        if (remainingTtl == null || remainingTtl < 0) return true;

        // Probability increases as TTL approaches 0
        double probability = Math.exp(-remainingTtl / (originalTtl * beta));
        return random.nextDouble() < probability;
    }
}
```

### Memory Management

```bash
# Redis configuration for production
maxmemory 1gb
maxmemory-policy allkeys-lru
```

**Eviction Policies:**

| Policy | Description |
|--------|-------------|
| `noeviction` | Return errors when memory full (default) |
| `allkeys-lru` | Evict least recently used keys |
| `allkeys-lfu` | Evict least frequently used keys |
| `volatile-lru` | Evict LRU keys with TTL only |
| `volatile-ttl` | Evict keys with shortest TTL |

---

## 14. Quick Reference

### Annotation Cheat Sheet

```java
// Cache the result
@Cacheable(value = "cache-name", key = "#param")

// Update cache with method result
@CachePut(value = "cache-name", key = "#param")

// Remove from cache
@CacheEvict(value = "cache-name", key = "#param")

// Remove all entries
@CacheEvict(value = "cache-name", allEntries = true)

// Multiple operations
@Caching(
    cacheable = @Cacheable(...),
    evict = {@CacheEvict(...), @CacheEvict(...)}
)

// Conditional caching
@Cacheable(value = "cache", condition = "#id > 0")
@Cacheable(value = "cache", unless = "#result == null")
```

### SpEL Key Expressions

```java
key = "#id"                    // Method parameter
key = "#user.id"               // Object property
key = "#p0"                    // First parameter (positional)
key = "#a0"                    // Alias for #p0
key = "#root.method.name"      // Method name
key = "#root.target"           // Target object
key = "#root.args[0]"          // First argument
key = "'prefix:' + #id"        // Concatenation
key = "T(java.util.UUID).randomUUID().toString()"  // Static method
```

### TTL Guidelines

| Data Type | Suggested TTL | Reasoning |
|-----------|---------------|-----------|
| User sessions | 30 min | Security |
| Frequently changing data | 1-5 min | Freshness |
| Reference data | 1-24 hours | Stability |
| Configuration | 5-60 min | Controlled updates |
| Computed results | 15-60 min | Expensive to recompute |

### Common Redis CLI Commands

```bash
redis-cli KEYS "*"              # List all keys
redis-cli GET key               # Get value
redis-cli TTL key               # Get TTL
redis-cli DEL key               # Delete key
redis-cli FLUSHDB               # Clear current database
redis-cli INFO memory           # Memory stats
redis-cli MONITOR               # Watch all commands
```

---

## Conclusion

This guide covered the complete Redis caching implementation in the Echno Backend project. Key takeaways:

1. **Cache at the service layer** using DTOs, not entities
2. **Use appropriate TTLs** based on data volatility
3. **Always handle cache eviction** for write operations
4. **Test your caching logic** to verify expected behavior
5. **Monitor cache metrics** to optimize performance
6. **Plan for failures** - the app should work without cache

For more complex scenarios, consider:
- Two-level caching for ultra-low latency
- Redis Cluster for horizontal scaling
- Pub/Sub for cache invalidation across services

---

## Additional Resources

- [Spring Cache Documentation](https://docs.spring.io/spring-framework/docs/current/reference/html/integration.html#cache)
- [Redis Documentation](https://redis.io/documentation)
- [Lettuce Reference](https://lettuce.io/core/release/reference/)
- [Caffeine Cache](https://github.com/ben-manes/caffeine)

---

*Document created: 2024*
*Last updated: December 2024*
*Project: Echno Backend*
