package org.tornotron.echno_backend.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring's own request errors keep the status they carry (#860). A GET on a POST-only path and
 * a missing required query parameter both used to reach the catch-all handler and answer 500,
 * which reported a client mistake as a server fault.
 */
class FrameworkRequestErrorStatusTest {

    @RestController
    static class Endpoints {

        @PostMapping(value = "/organization", consumes = MediaType.APPLICATION_JSON_VALUE)
        String create(@RequestBody String body) {
            return body;
        }

        @GetMapping("/conversion-status")
        String conversionStatus(@RequestParam boolean converted) {
            return String.valueOf(converted);
        }

        @GetMapping("/needs-header")
        String needsHeader(@RequestHeader("X-Org") String org) {
            return org;
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Endpoints())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void unsupportedMethod_isA405NamingTheAllowedMethod() throws Exception {
        mvc.perform(get("/organization"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void missingRequiredParameter_isA400NamingTheParameter() throws Exception {
        mvc.perform(get("/conversion-status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", containsString("converted")));
    }

    @Test
    void missingRequiredHeader_isA400() throws Exception {
        mvc.perform(get("/needs-header"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedContentType_isA415() throws Exception {
        mvc.perform(post("/organization").contentType(MediaType.APPLICATION_PDF).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
