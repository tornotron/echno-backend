package org.tornotron.echno_backend.common.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.OptBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An unreadable request body says what is wrong with it (#872). Every failure used to answer
 * "Required request body is missing", which hid a date-time format mismatch behind an outage.
 */
class UnreadableRequestBodyTest {

    enum Outcome {
        PASSED("passed"), FAILED("failed");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        @JsonValue
        String value() {
            return value;
        }
    }

    enum Plain { ONE, TWO }

    record Item(Outcome status) {
    }

    record Payload(@JsonFormat(lenient = OptBoolean.FALSE) LocalDateTime at, Integer count, Plain plain, List<Item> items) {
    }

    @RestController
    static class Endpoints {

        @PostMapping("/payload")
        String accept(@RequestBody Payload payload) {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Endpoints())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/payload").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Malformed Request"));
    }

    @Test
    void anEmptyBody_isReportedAsMissing() throws Exception {
        send("").andExpect(jsonPath("$.detail").value("Required request body is missing"));
    }

    @Test
    void aDateTimeWithATrailingZ_namesTheFieldAndTheFormatWithoutTheValue() throws Exception {
        send("{\"at\":\"2026-10-29T10:15:30Z\"}")
                .andExpect(jsonPath("$.detail", allOf(
                        containsString("Field 'at' must be"),
                        containsString("no time zone"))))
                .andExpect(jsonPath("$.errors.at", containsString("no time zone")))
                .andExpect(content().string(not(containsString("2026-10-29T10:15:30Z"))));
    }

    @Test
    void aValueOffTheAllowedList_namesItsPathAndTheJsonValues() throws Exception {
        send("{\"items\":[{\"status\":\"secret-pass\"}]}")
                .andExpect(jsonPath("$.detail").value("Field 'items[0].status' must be one of: passed, failed"))
                .andExpect(content().string(not(containsString("secret-pass"))));
    }

    @Test
    void anEnumWithoutAJsonValue_listsItsNames() throws Exception {
        send("{\"plain\":\"THREE\"}")
                .andExpect(jsonPath("$.detail").value("Field 'plain' must be one of: ONE, TWO"));
    }

    @Test
    void textWhereANumberIsExpected_saysSo() throws Exception {
        send("{\"count\":\"lots\"}")
                .andExpect(jsonPath("$.detail").value("Field 'count' must be a whole number"))
                .andExpect(content().string(not(containsString("lots"))));
    }

    @Test
    void anObjectWhereAListIsExpected_saysSo() throws Exception {
        send("{\"items\":{\"status\":\"passed\"}}")
                .andExpect(jsonPath("$.detail").value("Field 'items' must be a list"));
    }

    @Test
    void textThatIsNotJson_isReportedAsSuchWithItsPosition() throws Exception {
        send("{\"count\": secret")
                .andExpect(jsonPath("$.detail", allOf(
                        containsString("not valid JSON"),
                        containsString("line 1"))))
                .andExpect(content().string(not(containsString("secret"))));
    }
}
