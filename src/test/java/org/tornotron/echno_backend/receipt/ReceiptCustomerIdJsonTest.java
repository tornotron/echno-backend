package org.tornotron.echno_backend.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.receipt.dto.ReceiptCreationDto;
import org.tornotron.echno_backend.receipt.dto.ReceiptUpdateDto;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A receipt names its customer by the finance customer's UUID (#864). A client built against the
 * old numeric contract keeps working: a number is read as no customer, since it cannot name one.
 */
class ReceiptCustomerIdJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void aUuidIsRead() throws Exception {
        UUID id = UUID.fromString("3f2b8c1e-5d4a-4c7e-9b1a-2e6f0d9c8a71");

        ReceiptCreationDto dto = mapper.readValue(
                "{\"customerId\":\"" + id + "\"}", ReceiptCreationDto.class);

        assertThat(dto.getCustomerId()).isEqualTo(id);
    }

    @Test
    void aNumberFromAnOlderClientIsReadAsNoCustomer() throws Exception {
        ReceiptCreationDto created = mapper.readValue("{\"customerId\":12}", ReceiptCreationDto.class);
        ReceiptUpdateDto updated = mapper.readValue("{\"customerId\":12}", ReceiptUpdateDto.class);

        assertThat(created.getCustomerId()).isNull();
        assertThat(updated.getCustomerId()).isNull();
    }

    @Test
    void aStringThatIsNotAUuidIsRefused() {
        assertThatThrownBy(() -> mapper.readValue("{\"customerId\":\"abc\"}", ReceiptCreationDto.class))
                .isInstanceOf(InvalidFormatException.class);
    }
}
