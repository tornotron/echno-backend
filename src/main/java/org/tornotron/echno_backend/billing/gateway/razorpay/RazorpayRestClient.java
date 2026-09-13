package org.tornotron.echno_backend.billing.gateway.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * The thin HTTP layer under the Razorpay adapter: Razorpay's REST API over Spring's
 * {@code RestClient} with HTTP basic auth on the key pair, the same client and proxy
 * conventions as the compliance AI service. Deliberately not the Razorpay SDK, so the
 * dependency surface stays what the rest of the backend already has.
 */
public class RazorpayRestClient {

    private final BillingGatewayProperties.Razorpay props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RazorpayRestClient(BillingGatewayProperties.Razorpay props) {
        this.props = props;
        String baseUrl = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim().toLowerCase();
        // The key pair rides on every request as basic auth, so the transport has to be TLS. A
        // loopback URL is allowed for a local stub.
        boolean loopback = baseUrl.startsWith("http://localhost") || baseUrl.startsWith("http://127.0.0.1");
        if (!baseUrl.startsWith("https://") && !loopback) {
            throw new IllegalArgumentException(
                    "echno.billing.razorpay.base-url must be https (credentials are sent on every request): " + props.getBaseUrl());
        }
    }

    public JsonNode post(String path, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, body);
    }

    public JsonNode patch(String path, Map<String, Object> body) {
        return exchange(HttpMethod.PATCH, path, body);
    }

    public JsonNode get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    private JsonNode exchange(HttpMethod method, String path, Map<String, Object> body) {
        try {
            RestClient.RequestBodySpec request = client().method(method)
                    .uri(path)
                    .header("Authorization", basicAuth())
                    .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(body));
            }
            String response = request.retrieve().body(String.class);
            return response == null ? objectMapper.nullNode() : objectMapper.readTree(response);
        } catch (IOException e) {
            throw new BillingGatewayException("Razorpay " + method + " " + path + " returned unreadable JSON", e);
        } catch (RuntimeException e) {
            // The provider's own response text names the endpoint and the account; it belongs
            // in the cause chain for whoever is debugging, not in anything a caller might surface.
            throw new BillingGatewayException("Razorpay " + method + " " + path + " failed: " + e.getMessage(), e);
        }
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.getReadTimeoutSeconds()));
        if (props.getProxyHost() != null && !props.getProxyHost().isBlank()) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(props.getProxyHost(), props.getProxyPort())));
        }
        return RestClient.builder().requestFactory(factory).baseUrl(props.getBaseUrl()).build();
    }

    private String basicAuth() {
        String pair = props.getKeyId() + ":" + props.getKeySecret();
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
