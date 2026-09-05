package beer.foobar.virtuprobe.burp.client;

import beer.foobar.virtuprobe.burp.config.BridgeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin HTTP client for the VirtuProbe Burp bridge API. Deliberately uses the JDK HTTP client rather
 * than Montoya's {@code api.http()}: that one is for the target under test, and this talks to the
 * user's own VirtuProbe server. The bearer token is attached when configured; on a same machine
 * loopback VirtuProbe does not require it, so a default desktop setup works with none.
 */
public class VirtuProbeClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Imports a captured request as an HTTP probe. Returns the server's JSON response body. */
    public String importRequest(BridgeConfig config, byte[] rawRequest, String host, int port,
                                boolean tls, boolean verbatim) throws Exception {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("rawBase64", Base64.getEncoder().encodeToString(rawRequest));
        body.put("host", host);
        body.put("port", port);
        body.put("tls", tls);
        body.put("mode", verbatim ? "verbatim" : "structured");
        return post(config, "/burp/request", body);
    }

    /** True when VirtuProbe answers its health check at the configured address. */
    public boolean testConnection(BridgeConfig config) {
        try {
            final HttpResponse<String> response = http.send(
                    get(config, "/health/check"), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    private String post(BridgeConfig config, String path, Object body) throws Exception {
        final byte[] json = mapper.writeValueAsBytes(body);
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json));
        withToken(builder, config);
        final HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("VirtuProbe returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return response.body();
    }

    private HttpRequest get(BridgeConfig config, String path) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        withToken(builder, config);
        return builder.build();
    }

    private void withToken(HttpRequest.Builder builder, BridgeConfig config) {
        if (config.token() != null && !config.token().isBlank()) {
            builder.header("Authorization", "Bearer " + config.token());
        }
    }
}
