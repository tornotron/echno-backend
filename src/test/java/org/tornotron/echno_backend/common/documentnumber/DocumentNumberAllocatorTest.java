package org.tornotron.echno_backend.common.documentnumber;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the shape of the allocation: the number the caller gets back, and the one
 * statement it is allowed to take to get there. The concurrency behaviour that statement buys
 * is proven against a real database in {@link DocumentNumberAllocatorConcurrencyIT}.
 */
@ExtendWith(MockitoExtension.class)
class DocumentNumberAllocatorTest {

    private static final String ZONE = "Asia/Kolkata";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private int currentYear() {
        return LocalDate.now(ZoneId.of(ZONE)).getYear();
    }

    @Test
    void allocate_formatsThePrefixYearAndPaddedSequence() {
        DocumentNumberAllocator allocator = new DocumentNumberAllocator(jdbcTemplate, ZONE);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);

        assertThat(allocator.allocate(DocumentNumberType.PURCHASE_ORDER, 42L))
                .isEqualTo("PO-" + currentYear() + "-000007");
    }

    @Test
    void allocate_padsToSixDigitsButDoesNotTruncateBeyondThem() {
        DocumentNumberAllocator allocator = new DocumentNumberAllocator(jdbcTemplate, ZONE);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1234567L);

        assertThat(allocator.allocate(DocumentNumberType.SITE_TRANSFER, 42L))
                .isEqualTo("TRF-" + currentYear() + "-1234567");
    }

    @Test
    void allocate_usesEachTypesOwnPrefix() {
        DocumentNumberAllocator allocator = new DocumentNumberAllocator(jdbcTemplate, ZONE);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        assertThat(allocator.allocate(DocumentNumberType.INDENT, 1L)).startsWith("IND-");
        assertThat(allocator.allocate(DocumentNumberType.GOODS_RECEIVED_NOTE, 1L)).startsWith("GRN-");
    }

    /**
     * The counter has to be read and written by one statement. Two statements, however they are
     * ordered, let two transactions read the same value before either writes, which is the fault
     * this whole change exists to remove.
     */
    @Test
    void allocate_readsAndWritesTheCounterInASingleUpsert() {
        DocumentNumberAllocator allocator = new DocumentNumberAllocator(jdbcTemplate, ZONE);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(3L);

        allocator.allocate(DocumentNumberType.PURCHASE_ORDER, 42L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class),
                args.capture(), args.capture(), args.capture());

        String statement = sql.getValue().toUpperCase();
        assertThat(statement).contains("INSERT INTO DOCUMENT_NUMBER_SEQUENCE");
        assertThat(statement).contains("ON CONFLICT");
        assertThat(statement).contains("DO UPDATE SET LAST_ALLOCATED = DOCUMENT_NUMBER_SEQUENCE.LAST_ALLOCATED + 1");
        assertThat(statement).contains("RETURNING LAST_ALLOCATED");

        assertThat(args.getAllValues())
                .containsExactly(42L, DocumentNumberType.PURCHASE_ORDER.name(), currentYear());
    }

    @Test
    void allocate_readsTheYearInTheConfiguredZoneNotTheContainers() {
        // A zone whose calendar date is a day ahead of UTC for part of every day. The assertion
        // is that the allocator asks the zone rather than the JVM default, which on the servers
        // is UTC.
        String zone = "Pacific/Kiritimati";
        DocumentNumberAllocator allocator = new DocumentNumberAllocator(jdbcTemplate, zone);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        allocator.allocate(DocumentNumberType.PURCHASE_ORDER, 42L);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class),
                args.capture(), args.capture(), args.capture());
        assertThat(args.getAllValues().get(2)).isEqualTo(LocalDate.now(ZoneId.of(zone)).getYear());
    }
}
