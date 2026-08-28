package org.tornotron.echno_backend.common.documentnumber;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Hands out the next document number for a tenant, a document family and a year.
 *
 * <p>Numbers used to be invented by the browser from the list it happened to have loaded, so
 * two people on the New Purchase Order screen at the same moment proposed the same number and
 * the second save was rejected. The number is issued here instead, from a counter row per
 * {@code (organization, document type, year)} in {@code document_number_sequence}.
 *
 * <h2>Why one statement</h2>
 *
 * <p>The allocation is a single {@code INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING}.
 * Read-then-write would be wrong here whatever isolation the database ran at, because two
 * transactions can read the same counter before either writes. As one statement, the row is
 * the point of conflict:
 *
 * <ul>
 *   <li>The first transaction to touch the counter row leaves a write intent on it. The second
 *       blocks on that intent rather than reading around it, and only proceeds once the first
 *       has committed or aborted, at which point it increments the value the first left.</li>
 *   <li>If waiting pushes the second transaction's timestamp past something it has already
 *       read, CockroachDB aborts it with SQLSTATE 40001 rather than let it commit on a stale
 *       snapshot. The whole unit of work then runs again through
 *       {@link org.tornotron.echno_backend.common.retry.TransactionRetryTemplate}, which
 *       re-reads the counter and allocates the next number after the winner's.</li>
 * </ul>
 *
 * <p>Either way, two committed transactions cannot come away with the same number: that would
 * require both to have read the same counter value and both to have committed, which is exactly
 * what SERIALIZABLE forbids.
 *
 * <h2>What the caller still owes</h2>
 *
 * <p>This runs in the caller's transaction, so the counter advances only if the create commits;
 * a rolled back create returns its number to the pool rather than leaving a gap. The cost is
 * that the counter row stays locked until the caller commits, which serialises concurrent
 * creates of the same document type within one tenant. That is the intended trade at the volume
 * these documents are raised, and it is why the lock is taken as late as the create flow allows.
 *
 * <p>The per-organization unique index on the number column is the backstop, not the mechanism.
 * If a number ever were issued twice (a counter seeded behind the rows already in the table, say)
 * the insert is rejected with SQLSTATE 23505, and the callers nominate that as a retryable
 * conflict so the next attempt allocates a fresh number instead of answering the user with a 500.
 */
@Component
public class DocumentNumberAllocator {

    /**
     * Allocates and formats in one round trip. {@code last_allocated} holds the highest number
     * issued so far, so a first allocation inserts 1 and every later one returns the value it
     * has just written.
     */
    private static final String ALLOCATE_SQL = """
            INSERT INTO document_number_sequence
                (organization_id, document_type, sequence_year, last_allocated, created_at, updated_at)
            VALUES (?, ?, ?, 1, current_timestamp, current_timestamp)
            ON CONFLICT (organization_id, document_type, sequence_year)
            DO UPDATE SET last_allocated = document_number_sequence.last_allocated + 1,
                          updated_at = current_timestamp
            RETURNING last_allocated
            """;

    /** Matches the six-digit padding the browser used, so old and new numbers sort together. */
    private static final String NUMBER_FORMAT = "%s-%d-%06d";

    private final JdbcTemplate jdbcTemplate;
    private final ZoneId zone;

    public DocumentNumberAllocator(
            JdbcTemplate jdbcTemplate,
            @Value("${echno.document-number.zone:Asia/Kolkata}") String zone) {
        this.jdbcTemplate = jdbcTemplate;
        this.zone = ZoneId.of(zone);
    }

    /**
     * Issues the next number of {@code type} for {@code organizationId} in the current year.
     *
     * <p>Must be called inside a transaction, and inside the same one that saves the document:
     * the number is only spent if that transaction commits.
     *
     * @param type           the document family, which decides the prefix and the counter row
     * @param organizationId the tenant the counter belongs to
     * @return the formatted number, for example {@code PO-2026-000007}
     */
    public String allocate(DocumentNumberType type, Long organizationId) {
        int year = LocalDate.now(zone).getYear();
        Long sequence = jdbcTemplate.queryForObject(
                ALLOCATE_SQL, Long.class, organizationId, type.name(), year);
        return NUMBER_FORMAT.formatted(type.getPrefix(), year, sequence);
    }
}
