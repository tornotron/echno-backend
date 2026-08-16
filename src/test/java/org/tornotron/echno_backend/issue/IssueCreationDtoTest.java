package org.tornotron.echno_backend.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(dto.getStatus()).isEqualTo("open");
    }

    @Test
    void bindsCanonicalTypeField() throws Exception {
        IssueCreationDto dto = mapper.readValue(
                "{\"title\":\"A title\",\"description\":\"A description\",\"type\":\"quality\",\"status\":\"open\"}",
                IssueCreationDto.class);

        assertThat(dto.getType()).isEqualTo("quality");
    }
}
