package org.tornotron.echno_backend.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.enums.IssueStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The create endpoint parses {@link IssueCreationDto} from a multipart string
 * part, so its Jackson binding is exercised directly rather than through
 * {@code @RequestBody}.
 *
 * <p>The domain category is called {@code type} and nothing else. echno-core sent
 * it as {@code issueType} until 2.2.0, and this payload carried a
 * {@code @JsonAlias} for exactly as long as that client was deployed. These pin
 * what is accepted now the alias is gone: the canonical name binds, and the old
 * one does not quietly go on working.
 */
class IssueCreationDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsCanonicalTypeField() throws Exception {
        IssueCreationDto dto = mapper.readValue(
                "{\"title\":\"A title\",\"description\":\"A description\",\"type\":\"quality\",\"status\":\"open\"}",
                IssueCreationDto.class);

        assertThat(dto.getType()).isEqualTo("quality");
        assertThat(dto.getStatus()).isEqualTo(IssueStatus.open);
    }

    /**
     * The alias is gone, so a payload that names only {@code issueType} leaves
     * {@code type} unset. Spring's mapper ignores the unknown key rather than
     * failing on it, which is why the refusal has to come from {@code @NotNull}
     * on {@code type} and not from binding. Asserted here so the removal shows up
     * as a null rather than as a field that mysteriously still works.
     */
    @Test
    void leavesTypeUnsetWhenOnlyTheRetiredNameIsSent() throws Exception {
        ObjectMapper springMapper = Jackson2ObjectMapperBuilder.json().build();

        IssueCreationDto dto = springMapper.readValue(
                "{\"title\":\"A title\",\"description\":\"A description\",\"issueType\":\"safety\",\"status\":\"open\"}",
                IssueCreationDto.class);

        assertThat(dto.getType()).isNull();
    }

    /**
     * The reason a field can be taken off a create payload without sequencing the deploy behind
     * the web client's. {@link Jackson2ObjectMapperBuilder} is what Spring Boot's Jackson
     * auto-configuration builds the injected mapper from, and it leaves
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} off; nothing in {@code application.yml} turns it back
     * on. So a client still sending a field the payload no longer declares is not broken by the
     * backend going first, and a client that has stopped sending one is not broken by it going
     * second.
     */
    @Test
    void ignoresAFieldThePayloadNoLongerDeclares() {
        ObjectMapper springMapper = Jackson2ObjectMapperBuilder.json().build();

        assertThatCode(() -> springMapper.readValue(
                "{\"title\":\"A title\",\"description\":\"A description\",\"type\":\"quality\","
                        + "\"aFieldThisPayloadDoesNotHave\":\"anything\"}",
                IssueCreationDto.class))
                .doesNotThrowAnyException();
    }
}
