package org.tornotron.echno_backend.common.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.chat.dto.SendMessageDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The binder's own contract: a payload cannot leave it unvalidated.
 *
 * <p>This is the property the eleven endpoints of issue #490 were missing, so it is pinned here
 * once rather than re-tested per endpoint. {@link SendMessageDto} stands in for any payload; it is
 * used because it is the smallest one that declares a constraint.
 *
 * <p>A real Hibernate Validator and a real {@code ObjectMapper}, no Spring context: whether a
 * constraint fires needs a genuine validator, and a mocked one would pin nothing.
 */
class JsonPartBinderTest {

    private static ValidatorFactory factory;
    private static JsonPartBinder binder;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        binder = new JsonPartBinder(new ObjectMapper(), new PayloadValidator(factory.getValidator()));
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void read_refusesAPayloadThatFailsAConstraint() {
        assertThatThrownBy(() -> binder.read("{\"content\":\"   \"}", SendMessageDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void read_refusesAPayloadMissingARequiredField() {
        assertThatThrownBy(() -> binder.read("{\"replyToId\":1198}", SendMessageDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void read_returnsAValidPayload() throws JsonProcessingException {
        SendMessageDto dto = binder.read(
                "{\"content\":\"Concrete pour on block C is done.\",\"replyToId\":1198}",
                SendMessageDto.class);

        assertThat(dto.getContent()).isEqualTo("Concrete pour on block C is done.");
        assertThat(dto.getReplyToId()).isEqualTo(1198L);
    }

    @Test
    void read_refusesAnAbsentPart() {
        assertThatThrownBy(() -> binder.read(null, SendMessageDto.class))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> binder.read("   ", SendMessageDto.class))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void read_refusesAPartThatIsNotJson() {
        assertThatThrownBy(() -> binder.read("not json at all", SendMessageDto.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    /**
     * A partial update sends only the fields it is changing, so there is no bean and nothing to
     * validate, and an absent part means "change nothing" rather than a bad request.
     */
    @Test
    void readUpdates_readsTheFieldsSent_andTreatsAnAbsentPartAsNoChange() throws JsonProcessingException {
        assertThat(binder.readUpdates("{\"title\":\"Repaint block B\",\"progress\":40}"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("title", "Repaint block B", "progress", 40));

        assertThat(binder.readUpdates(null)).isEmpty();
    }
}
