package org.tornotron.echno_backend.common.ratelimit;

import com.giffing.bucket4j.spring.boot.starter.config.condition.ConditionalOnBucket4jEnabled;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * There is no path-matched rate limit in front of the API, and this is what keeps it that way.
 *
 * <p>A blanket filter over {@code /api/**} was configured here for a long time and never ran,
 * because the starter that would install it was switched off. Switching it on as written would
 * have refused ordinary traffic rather than limiting anything, for a reason that is a property
 * of where this application sits rather than of the numbers chosen: at the origin there is no
 * per-client address to key on. The console reaches the backend through its own server-side
 * proxy, which opens the connection itself and forwards three headers, none of them a client
 * address, so every request from every signed-in person arrives wearing one address. A limit
 * keyed on that is one allowance shared by the whole console.
 *
 * <p>So volumetric limiting belongs at the edge, which is the only place a real client address
 * exists, and limiting by meaning belongs in the service, keyed on the authenticated principal
 * and charged by outcome, as invite-code redemption does. The middle position, a path-matched
 * filter at the origin, can do neither: it runs before authentication, so it has no principal,
 * and the address available to it is not the client's.
 *
 * <p>The tests below pin the facts that argument rests on: that no block is configured, that the
 * flag keeping the starter out is load bearing rather than leftover, and that the address the
 * application sees is asserted by the nearest hop rather than observed.
 */
class PathMatchedRateLimitAbsenceTest {

    private static final String CLIENT = "203.0.113.7";
    private static final String NEAREST_HOP = "198.51.100.4";

    private final List<PropertySource<?>> shipped = loadShippedConfiguration();

    @Test
    void noFilterBlockIsConfiguredOverTheApi() {
        assertThat(propertyNames())
                .as("a bucket4j filter block is a blanket limit over whatever path it matches, "
                        + "keyed on an address the origin cannot attribute to a client")
                .noneMatch(name -> name.startsWith("bucket4j.filters"));
    }

    @Test
    void theStarterIsShippedOff() {
        assertThat(property("bucket4j.enabled"))
                .as("the starter installs its filters by auto-configuration, so the flag is the "
                        + "only thing that decides whether a block someone adds later is live")
                .isEqualTo(false);
    }

    @Test
    void theOffSwitchIsLoadBearingRatherThanDecoration() {
        ConditionalOnProperty condition = ConditionalOnBucket4jEnabled.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.matchIfMissing())
                .as("the starter defaults to on, so deleting bucket4j.enabled would enable it "
                        + "rather than leave things as they are; this fails if an upgrade "
                        + "changes that default, which is when the line could be dropped")
                .isTrue();
    }

    @Test
    void theApplicationIsConfiguredToReadTheForwardedClientAddress() {
        assertThat(property("server.forward-headers-strategy"))
                .as("without this every access log line records the nearest hop instead of the "
                        + "caller, which is worth having on its own merits")
                .isEqualTo("FRAMEWORK");
    }

    @Test
    void theAddressTheApplicationSeesIsWhateverTheNearestHopAsserts() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/project");
        request.setRemoteAddr(NEAREST_HOP);
        request.addHeader("X-Forwarded-For", CLIENT);

        assertThat(remoteAddressAfterForwardedHeaderFilter(request))
                .as("with forwarding on, the address is taken from a header the request carries "
                        + "rather than from the socket, which is the fact that decides where a "
                        + "limit keyed on an address can live: only a hop that sets the header "
                        + "itself knows the value is the caller's")
                .isEqualTo(CLIENT);
    }

    private static String remoteAddressAfterForwardedHeaderFilter(MockHttpServletRequest request)
            throws IOException, ServletException {
        MockFilterChain chain = new MockFilterChain();
        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(), chain);
        return ((HttpServletRequest) chain.getRequest()).getRemoteAddr();
    }

    private static List<PropertySource<?>> loadShippedConfiguration() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("the shipped application.yml could not be read", e);
        }
    }

    private List<String> propertyNames() {
        List<String> names = new ArrayList<>();
        for (PropertySource<?> source : shipped) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                Collections.addAll(names, enumerable.getPropertyNames());
            }
        }
        return names;
    }

    private Object property(String name) {
        for (PropertySource<?> source : shipped) {
            Object value = source.getProperty(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
