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
 * {@code @RequestBody}. The web client (echno-core) sends the domain category as
 * {@code issueType}; these tests pin that it binds to {@code type} (a mismatch
 * previously left {@code type} null and crashed create with a 500).
 */
class IssueCreationDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsWebClientIssueTypeAliasOntoType() throws Exception {
        IssueCreationDto dto = mapper.readValue(
                "{\"title\":\"A title\",\"description\":\"A description\",\"issueType\":\"safety\",\"status\":\"open\"}",
                IssueCreationDto.class);

        assertThat(dto.getType()).isEqualTo("safety");
        assertThat(dto.getStatus()).isEqualTo(IssueStatus.open);
    }

    @Test
    void bindsCanonicalTypeField() throws Exception {
        IssueCreationDto dto = mapper.readValue(
                "{\"title\":\"A title\",\"description\":\"A description\",\"type\":\"quality\",\"status\":\"open\"}",
                IssueCreationDto.class);

        assertThat(dto.getType()).isEqualTo("quality");
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
