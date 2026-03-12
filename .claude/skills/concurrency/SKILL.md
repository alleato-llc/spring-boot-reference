---
name: concurrency
description: Concurrency patterns for Spring Boot applications. Virtual threads as default, structured concurrency for fan-out, CompletableFuture composition rules, connection pool protection, context propagation. Use when adding parallel or asynchronous work to a Spring Boot application.
version: 1.0.0
---

# Concurrency

Concurrency in Spring Boot with virtual threads, structured concurrency, and disciplined use of `CompletableFuture`. The default is sequential code — add concurrency only when there is a measured need (I/O fan-out, bulk processing, external process orchestration). Unnecessary concurrency adds complexity, obscures failures, and makes debugging harder.

## Rules

### 1. Default to sequential

Do not wrap a blocking call in `CompletableFuture.supplyAsync()` just because it involves I/O. A single HTTP call, a single database query, or a single SDK call should remain synchronous. Concurrency is justified when:

- Multiple independent I/O operations can run in parallel (fan-out to 3 services)
- Bulk data requires partitioned processing (10k records across workers)
- An external process must run without blocking the request thread

If none of these apply, keep the code sequential.

```java
// BAD — concurrency adds nothing here
CompletableFuture<Payment> payment = CompletableFuture.supplyAsync(
    () -> paymentClient.charge(order)
);
Payment result = payment.join(); // blocks anyway

// GOOD — just call it
Payment result = paymentClient.charge(order);
```

### 2. Prefer virtual threads

Virtual threads (JEP 444) are the default threading model. They are cheap to create, block without consuming platform threads, and eliminate the need for reactive frameworks or thread pool tuning for I/O-bound work.

Spring Boot auto-configures virtual threads when enabled:

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

This switches Tomcat, `@Async`, and `TaskExecutor` to virtual threads. No code changes required for request-handling concurrency — each request already runs on its own virtual thread.

**When to use platform threads instead:**
- CPU-bound computation that saturates cores (compression, hashing large payloads)
- Native library calls that assume thread-local storage
- Code that relies on `ThreadLocal` with platform-thread identity

For these cases, configure a dedicated `ThreadPoolTaskExecutor` with bounded pool size.

### 3. Use structured concurrency for fan-out

Structured concurrency (JEP 462) scopes concurrent subtasks to a parent lifetime. If any subtask fails, the others are cancelled and the scope throws. This prevents fire-and-forget leaks and provides clear error handling.

```java
// Fan-out to three independent services
try (var scope = new PropagatingScope()) {
    Subtask<Payment> pendingPayment = scope.fork(() -> paymentClient.charge(order));
    Subtask<Reservation> pendingReservation = scope.fork(() -> inventoryClient.reserve(order));
    Subtask<Invoice> pendingInvoice = scope.fork(() -> invoiceService.generate(order));

    scope.join(); // waits for all subtasks

    // All succeeded — resolve and compose results
    var payment = pendingPayment.get();
    var reservation = pendingReservation.get();
    var invoice = pendingInvoice.get();

    order.withPaymentId(payment.id())
         .withReservationId(reservation.id())
         .withInvoiceUrl(invoice.url());
}
// If any subtask fails, scope.join() throws, all others are cancelled
```

**Fail-fast by default.** If payment fails, there is no reason to wait for inventory and invoice. Structured concurrency cancels siblings automatically. If you need partial results (some may fail), override `handleComplete` on a custom scope.

### 4. Compose CompletableFutures — never block mid-chain

When structured concurrency is not suitable (e.g., integrating with libraries that return `CompletableFuture`), compose futures end-to-end. Never call `.get()` or `.join()` in the middle of a chain — it blocks the current thread and defeats the purpose of async composition.

Two patterns for exposing async surfaces — both propagate context automatically.

**Inner accessor (`asAsync()`)** — keeps the async surface on the client itself. Each call to `asAsync()` creates a short-lived `Async` instance that captures the caller's context at construction:

```java
public class PaymentClient {
    public Payment charge(Order order) { ... }

    public Async asAsync() { return new Async(); }

    public class Async {
        private final ContextSnapshot snapshot = ContextSnapshot.captureAll();

        public CompletableFuture<Payment> charge(Order order) {
            return CompletableFuture.supplyAsync(() -> {
                try (var ignored = snapshot.setThreadLocals()) {
                    return PaymentClient.this.charge(order);
                }
            });
        }
    }
}
```

**Separate class (`AsyncContext` base)** — preferred when the async client is a Spring bean. `AsyncContext` captures context per-call in `withContext`:

```java
public abstract class AsyncContext {
    protected <T> CompletableFuture<T> withContext(Callable<T> task) {
        var snapshot = ContextSnapshot.captureAll();
        return CompletableFuture.supplyAsync(() -> {
            try (var ignored = snapshot.setThreadLocals()) {
                return task.call();
            }
        });
    }
}

public class AsyncPaymentClient extends AsyncContext {
    private final PaymentClient paymentClient;

    public CompletableFuture<Payment> charge(Order order) {
        return withContext(() -> paymentClient.charge(order));
    }
}
```

Use `asAsync()` when you want the async surface co-located with the synchronous client. Use `AsyncContext` when the async client needs to be injected as a bean.

Compose futures end-to-end — block once at the terminal operation:

```java
// BAD — blocks mid-chain, wastes threads
CompletableFuture<Payment> pendingPayment = paymentClient.asAsync().charge(order);
Payment payment = pendingPayment.join(); // blocks
CompletableFuture<Invoice> pendingInvoice = invoiceService.asAsync().generate(order, payment);
Invoice invoice = pendingInvoice.join(); // blocks again

// GOOD — composed, single terminal operation
CompletableFuture<OrderResult> pendingResult = paymentClient.asAsync().charge(order)
    .thenCompose(payment -> invoiceService.asAsync().generate(order, payment))
    .thenApply(invoice -> new OrderResult(order, invoice));

// Terminal — block once at the boundary
OrderResult orderResult = pendingResult.join();
```

For independent futures, use `allOf` to wait for all. Name unresolved futures with a `pending` prefix to distinguish them from resolved values:

```java
// With asAsync()
var pendingPayment = paymentClient.asAsync().charge(order);
var pendingReservation = inventoryClient.asAsync().reserve(order);

// Or with AsyncContext bean
var pendingPayment = asyncPaymentClient.charge(order);
var pendingReservation = asyncInventoryClient.reserve(order);

CompletableFuture.allOf(pendingPayment, pendingReservation).join();

var payment = pendingPayment.join();
var reservation = pendingReservation.join();

order.withPaymentId(payment.id())
     .withReservationId(reservation.id());
```

### 5. Limit thread context switching

Avoid bouncing between executors unnecessarily. When composing futures:

- Use `thenCompose` / `thenApply` (same thread) over `thenComposeAsync` / `thenApplyAsync` (different thread) unless the downstream step is CPU-intensive and the current thread is a virtual thread handling I/O
- If you must specify an executor, use a single shared executor for related work rather than creating new ones per operation
- Group related I/O together rather than alternating between computation and I/O across thread boundaries

### 6. Avoid @Async

Spring's `@Async` has silent pitfalls:

- **Swallowed exceptions** — void `@Async` methods lose exceptions unless you configure an `AsyncUncaughtExceptionHandler`
- **Broken transactions** — `@Transactional` is thread-bound; `@Async` runs on a different thread, so the transaction does not propagate
- **Hidden concurrency** — the caller sees a normal method signature and may not realize it runs asynchronously
- **Proxy-only** — self-invocation bypasses the proxy, so the method runs synchronously with no warning

Instead, make concurrency explicit: use structured concurrency or `CompletableFuture` at the call site so the concurrent behavior is visible in the code.

## Database concurrency

### Connection pool protection

Virtual threads make it trivial to spawn thousands of concurrent tasks, but the database connection pool is finite (default HikariCP: 10 connections). Unbounded concurrency will exhaust the pool and deadlock.

**First line of defense: HikariCP's built-in `connectionTimeout`.** HikariCP already queues connection requests when the pool is exhausted — threads block until a connection becomes available or the timeout expires. This is sufficient for normal request-handling load where Tomcat's thread concurrency naturally stays within pool bounds. Do not add a `Semaphore` for routine request handling — let HikariCP manage the queue.

A `Semaphore` is needed when **your code creates concurrency beyond what the framework manages** — e.g., forking thousands of tasks inside a `StructuredTaskScope`. In that case, HikariCP's timeout would fire on most tasks because they all compete simultaneously, rather than being naturally throttled.

```java
// BAD — 10,000 virtual threads competing for 10 connections
try (var scope = StructuredTaskScope.open()) {
    for (var item : tenThousandItems) {
        scope.fork(() -> repository.save(transform(item)));
    }
    scope.join(); // deadlock: all threads block waiting for connections
}

// GOOD — bounded concurrency with semaphore for self-managed fan-out
private static final Semaphore DB_PERMITS = new Semaphore(8); // below pool size

try (var scope = new PropagatingScope()) {
    for (var item : tenThousandItems) {
        scope.fork(() -> {
            DB_PERMITS.acquire();
            try {
                return repository.save(transform(item));
            } finally {
                DB_PERMITS.release();
            }
        });
    }
    scope.join();
}
```

Set the semaphore permits **below** the connection pool size to leave headroom for request-handling threads.

**When to add concurrency controls vs relying on HikariCP:**

| Scenario | Approach |
|---|---|
| Normal request handling (1 connection per request) | HikariCP `connectionTimeout` — no extra controls needed |
| Self-managed fan-out (`StructuredTaskScope`, bulk processing) | `Semaphore` below pool size |
| Long-lived background workers (job queues, scheduled tasks) | Bounded `ThreadPoolExecutor` with monitoring |

Rule of thumb: if you're already inside a `StructuredTaskScope`, use a `Semaphore`. If you're configuring a background worker that processes a queue indefinitely, use a bounded executor. If neither applies, trust HikariCP.

### Transaction boundaries

`@Transactional` binds to the current thread. Concurrent subtasks do **not** inherit the parent transaction — each gets its own (or none, if not annotated).

```java
// BAD — assumes shared transaction
@Transactional
public void processOrder(Order order) {
    try (var scope = StructuredTaskScope.open()) {
        scope.fork(() -> orderRepository.save(order));      // own transaction
        scope.fork(() -> auditRepository.log(order));       // own transaction
        scope.join();
    }
    // if auditRepository fails, orderRepository is already committed
}

// GOOD — single transaction for related writes, concurrency for independent I/O
@Transactional
public void processOrder(Order order) {
    orderRepository.save(order);
    auditRepository.log(order);

    // Concurrent I/O to external services (no shared transaction needed)
    try (var scope = new PropagatingScope()) {
        scope.fork(() -> paymentClient.charge(order));
        scope.fork(() -> notificationClient.notify(order));
        scope.join();
    }
}
```

**Rule:** Keep database writes in a single thread within one `@Transactional` method. Use concurrency only for independent I/O to external services.

## Edge cases

### Bulk data processing

For large datasets (thousands of records), stream records lazily and partition into batches with bounded concurrency. Prefer `Stream` over `List` for bulk input — it enables lazy evaluation and prevents requiring all records in memory before processing begins.

```java
public void processBulkImport(Stream<ExpenseRecord> records) {
    var semaphore = new Semaphore(4); // max 4 concurrent batches
    try (var scope = new PropagatingScope()) {
        BatchIterator.of(records, 100).forEach(batch -> {
            scope.fork(() -> {
                semaphore.acquire();
                try {
                    return processBatch(batch); // @Transactional per batch
                } finally {
                    semaphore.release();
                }
            });
        });
        scope.join();
    }
}
```

`List` is acceptable when the dataset fits comfortably in memory and is already materialized (e.g., a request body payload).

Key rules:
- Stream from the source (database cursor, paginated API, file reader) when possible
- Partition into batches (100–1000 records per batch)
- Bound concurrency with a `Semaphore` sized below the connection pool
- Each batch gets its own transaction
- A batch failure should not roll back other completed batches — handle partial failures explicitly

### Process invocation

External process execution (CLI tools, scripts) should use `ProcessBuilder`. `Process.waitFor()` is a blocking call — on a **virtual thread**, the JVM parks the virtual thread and releases the carrier thread, so other virtual threads continue executing. On a **platform thread**, it blocks the platform thread entirely.

**Synchronous (caller is already on a virtual thread):** If the caller is already on a virtual thread (e.g., a Tomcat request thread with virtual threads enabled), `waitFor()` is fine inline — the carrier is released while waiting.

```java
public ProcessResult runExport(Path outputPath) {
    var process = new ProcessBuilder("csvtool", "export", outputPath.toString())
        .redirectErrorStream(true)
        .start();

    // On a virtual thread, waitFor() parks this virtual thread and releases
    // the carrier thread — other virtual threads continue executing.
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();

    if (exitCode != 0) {
        throw new ExportException("csvtool failed: " + output);
    }
    return new ProcessResult(exitCode, output);
}
```

**Fire-and-detach:** When the caller should not wait for the process to complete, spawn a dedicated virtual thread:

```java
// Caller does not wait — process runs on its own virtual thread
var snapshot = ContextSnapshot.captureAll();
Thread.ofVirtual().name("export-process").start(() -> {
    try (var ignored = snapshot.setThreadLocals()) {
        var result = runExport(outputPath);
        log.info("Export completed: {}", result);
    } catch (Exception e) {
        log.error("Export failed", e);
    }
});
```

**Async with result:** Use structured concurrency to fork the process and join later:

```java
try (var scope = new PropagatingScope()) {
    var pendingExport = scope.fork(() -> runExport(outputPath));
    // ... do other work ...
    scope.join();
    var result = pendingExport.get();
}
```

General rules:
- Set timeouts: `process.waitFor(30, TimeUnit.SECONDS)`
- Always read stdout/stderr to prevent pipe buffer deadlocks
- Use `redirectErrorStream(true)` or read both streams concurrently
- Destroy the process in a `finally` block or try-with-resources

## Context propagation

MDC (mapped diagnostic context), trace IDs, and security context are thread-local. They do **not** propagate to child threads automatically.

### Micrometer context propagation

With `context-propagation` on the classpath, Spring auto-instruments `TaskExecutor` to propagate context. `StructuredTaskScope` is outside Spring's managed executors, so context does not propagate automatically to forked subtasks.

Use a `PropagatingScope` wrapper that captures context once at construction and restores it on every fork — this eliminates per-fork boilerplate and prevents accidentally losing context:

```java
public class PropagatingScope implements AutoCloseable {
    private final StructuredTaskScope scope;
    private final ContextSnapshot snapshot;

    public PropagatingScope() {
        this.snapshot = ContextSnapshot.captureAll();
        this.scope = StructuredTaskScope.open();
    }

    public <T> Subtask<T> fork(Callable<T> task) {
        return scope.fork(() -> {
            try (var ignored = snapshot.setThreadLocals()) {
                return task.call();
            }
        });
    }

    public void join() throws InterruptedException {
        scope.join();
    }

    @Override
    public void close() {
        scope.close();
    }
}
```

Usage — context propagation is automatic, no per-fork wrapping needed:

```java
try (var scope = new PropagatingScope()) {
    scope.fork(() -> paymentClient.charge(order));
    scope.fork(() -> inventoryClient.reserve(order));
    scope.join();
}
```

The snapshot is captured once at construction (on the caller's thread), then restored on each forked virtual thread when its callable executes. The `try`-with-resources inside `fork` ensures thread-locals are cleaned up after each task completes.

See the `tracing` skill for correlation ID propagation patterns across HTTP and messaging boundaries.

## Conventions

- Default to sequential code — add concurrency only for measured I/O fan-out, bulk processing, or process orchestration
- Enable `spring.threads.virtual.enabled: true` — virtual threads are the default
- Use structured concurrency (`StructuredTaskScope`) for fan-out with automatic cancellation
- Async surfaces use `asAsync()` inner accessor or `AsyncContext` base class — both propagate context automatically. Do not name methods `*Async`
- Name unresolved futures with a `pending` prefix (`pendingPayment`), resolved values get the plain name (`payment`)
- Compose `CompletableFuture` chains end-to-end — block once at the terminal operation, never mid-chain
- Prefer `thenCompose`/`thenApply` over their `*Async` variants to avoid unnecessary thread hops
- Do not use `@Async` — make concurrency explicit at the call site
- Trust HikariCP for normal request-handling concurrency — add a `Semaphore` only for self-managed fan-out beyond what the framework throttles
- Keep database writes in a single thread within `@Transactional` — use concurrency only for external I/O
- Prefer `Stream` over `List` for bulk input — stream from the source, partition into batches with bounded concurrency and per-batch transactions
- Propagate context (MDC, trace, security) via `PropagatingScope` instead of bare `StructuredTaskScope`
- Set timeouts on external process execution

## Checklist

- [ ] Concurrency is justified — sequential code cannot meet the requirement
- [ ] Virtual threads are enabled in `application.yml`
- [ ] Fan-out uses `StructuredTaskScope` with join and cancellation
- [ ] Async surfaces use `asAsync()` or `AsyncContext` with context propagation — no `*Async` method names
- [ ] Future variables use `pending` prefix; resolved values use plain domain names
- [ ] No `.get()` or `.join()` calls mid-chain in `CompletableFuture` composition
- [ ] No `@Async` annotations
- [ ] Self-managed fan-out with DB access is bounded by a `Semaphore` below pool size (normal request handling relies on HikariCP)
- [ ] All database writes within a `@Transactional` method happen on a single thread
- [ ] Fan-out uses `PropagatingScope` (not bare `StructuredTaskScope`) to propagate MDC / trace / security context
- [ ] External processes have timeouts and stream draining
- [ ] Bulk input uses `Stream` (not `List`) unless data is already materialized
- [ ] Bulk operations are partitioned with per-batch transactions
