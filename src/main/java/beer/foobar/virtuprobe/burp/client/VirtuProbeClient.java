package beer.foobar.virtuprobe.burp.client;

import beer.foobar.virtuprobe.burp.config.BridgeConfig;
import beer.foobar.virtuprobe.burp.model.BridgeCommand;
import beer.foobar.virtuprobe.burp.model.BridgeTargets;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for the VirtuProbe Burp bridge API. Deliberately uses the JDK HTTP client rather
 * than Montoya's {@code api.http()}: that one is for the target under test, and this talks to the
 * user's own VirtuProbe server. The bearer token is attached when configured; on a same machine
 * loopback VirtuProbe does not require it, so a default desktop setup works with none.
 *
 * <p><b>Unknown response fields are ignored, and that is a compatibility decision rather than
 * laziness.</b> An installed extension outlives the VirtuProbe it was built against, so a server
 * that adds a field to any of these payloads must not break it. The same default the other way round
 * is what made a new field on the subscription response a field-wide outage, and nothing here is
 * worth repeating that for.
 */
public class VirtuProbeClient {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** One request to import, as the extension holds it before it becomes JSON. */
    public record Capture(byte[] request, byte[] response, String host, int port, boolean tls) {
    }

    /** Where an import should land. A {@code newBundleName} creates a bundle instead of naming one. */
    public record Destination(String bundleId, String newBundleName, String projectId) {

        public boolean createsBundle() {
            return newBundleName != null && !newBundleName.isBlank();
        }
    }

    // --- Burp -> VirtuProbe -------------------------------------------------------------------

    /**
     * Imports one capture. {@code promote} asks VirtuProbe to build a regression chain asserting the
     * status the response carried, which is why the response is sent rather than dropped.
     */
    public String importOne(BridgeConfig config, Capture capture, String bundleId, boolean verbatim,
                            boolean promote) throws Exception {
        final Map<String, Object> body = requestBody(capture, verbatim);
        if (bundleId != null && !bundleId.isBlank()) {
            body.put("bundleId", bundleId);
        }
        if (promote) {
            body.put("promote", true);
            if (capture.response() != null && capture.response().length > 0) {
                body.put("responseBase64", Base64.getEncoder().encodeToString(capture.response()));
            }
        }
        return post(config, "/burp/request", body);
    }

    /**
     * Imports several captures in ONE call. The server creates the bundle when the destination names
     * a new one, and skips a request it cannot parse rather than failing the batch.
     *
     * <p>A single send goes through here too whenever it is not being promoted, so the batch path is
     * the one that gets exercised rather than a rarely used branch.
     */
    public String importMany(BridgeConfig config, List<Capture> captures, Destination destination,
                             boolean verbatim) throws Exception {
        final List<Map<String, Object>> items = new ArrayList<>();
        for (Capture capture : captures) {
            items.add(requestBody(capture, verbatim));
        }
        return post(config, "/burp/requests", bulkBody(destination, items));
    }

    /**
     * Creates a bundle and returns its id, by importing nothing into it.
     *
     * <p>Only the bulk endpoint can create a bundle by name, and only the single endpoint can promote
     * a capture to a regression test, so promoting into a brand new bundle needs the bundle to exist
     * first. An empty request list is a supported call for exactly this.
     */
    public String createBundle(BridgeConfig config, String name, String projectId) throws Exception {
        final String json = post(config, "/burp/requests",
                bulkBody(new Destination(null, name, projectId), List.of()));
        final String bundleId = mapper.readTree(json).path("bundleId").asText(null);
        if (bundleId == null || bundleId.isBlank()) {
            throw new IllegalStateException("VirtuProbe created no bundle for \"" + name + "\".");
        }
        return bundleId;
    }

    /** The bundles and projects the destination picker offers. */
    public BridgeTargets targets(BridgeConfig config) throws Exception {
        final HttpResponse<String> response = http.send(
                get(config, "/burp/targets", Duration.ofSeconds(10)), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("VirtuProbe returned HTTP " + response.statusCode()
                    + " for the destination list.");
        }
        final BridgeTargets targets = mapper.readValue(response.body(), BridgeTargets.class);
        return targets == null ? BridgeTargets.empty() : targets;
    }

    // --- VirtuProbe -> Burp -------------------------------------------------------------------

    /**
     * Long polls the command queue. Returns whatever is queued, empty on timeout.
     *
     * <p>The read timeout is deliberately longer than the wait the server is asked to hold for: they
     * would otherwise race, and losing that race looks like the server being unreachable rather than
     * a poll that simply found nothing.
     */
    public List<BridgeCommand> pollCommands(BridgeConfig config, int waitSeconds) throws Exception {
        final Duration readTimeout = Duration.ofSeconds(waitSeconds + 10L);
        final HttpResponse<String> response = http.send(
                get(config, "/burp/commands?wait=" + waitSeconds, readTimeout),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("VirtuProbe returned HTTP " + response.statusCode()
                    + " while polling for commands.");
        }
        final BridgeCommand[] commands = mapper.readValue(response.body(), BridgeCommand[].class);
        return commands == null ? List.of() : List.of(commands);
    }

    // --- connection checks --------------------------------------------------------------------

    /** True when VirtuProbe answers its health check at the configured address. */
    public boolean testConnection(BridgeConfig config) {
        try {
            final HttpResponse<String> response = http.send(
                    get(config, "/health/check", Duration.ofSeconds(5)), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    /** The outcome of a bridge check: whether the address answers, whether the bridge API is there, and whether it is enabled. */
    public record BridgeStatus(boolean reachable, boolean bridgePresent, boolean enabled, String message) {
    }

    /**
     * Checks the configured address against the bridge itself, not just the health endpoint.
     *
     * <p>{@code /burp/settings} is reachable whether or not the bridge is enabled, so a 200 confirms
     * both that the address serves the bridge API and whether the toggle is on. A 404 means the
     * address answered but does not serve {@code /burp} (pointing at the UI server rather than the
     * execution server is the usual cause). This is more useful than a plain health check, which a
     * front door with no bridge would still pass.
     */
    public BridgeStatus checkBridge(BridgeConfig config) {
        final String where = config.host() + ":" + config.port();
        try {
            final HttpResponse<String> response = http.send(
                    get(config, "/burp/settings", Duration.ofSeconds(5)), HttpResponse.BodyHandlers.ofString());
            final int code = response.statusCode();
            if (code == 200) {
                final boolean enabled = mapper.readTree(response.body()).path("enabled").asBoolean(false);
                return new BridgeStatus(true, true, enabled, enabled
                        ? "Connected. The Burp bridge is enabled."
                        : "Connected, but the Burp bridge is off. Enable it in VirtuProbe Settings.");
            }
            if (code == 401) {
                return new BridgeStatus(true, true, false,
                        "The bridge requires a token. Set the API token from VirtuProbe Settings.");
            }
            if (code == 404) {
                return new BridgeStatus(true, false, false,
                        "Reached a server at " + where + ", but it does not serve the bridge API. "
                                + "Point at the VirtuProbe execution server port.");
            }
            return new BridgeStatus(true, false, false, "VirtuProbe answered HTTP " + code + " at " + config.baseUrl() + ".");
        } catch (Exception e) {
            return new BridgeStatus(false, false, false, "Could not reach VirtuProbe at " + config.baseUrl() + ".");
        }
    }

    // --- plumbing ------------------------------------------------------------------------------

    private Map<String, Object> requestBody(Capture capture, boolean verbatim) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("rawBase64", Base64.getEncoder().encodeToString(capture.request()));
        body.put("host", capture.host());
        body.put("port", capture.port());
        body.put("tls", capture.tls());
        body.put("mode", verbatim ? "verbatim" : "structured");
        return body;
    }

    private Map<String, Object> bulkBody(Destination destination, List<Map<String, Object>> items) {
        final Map<String, Object> body = new LinkedHashMap<>();
        if (destination.createsBundle()) {
            body.put("bundleName", destination.newBundleName().trim());
            // An empty string is meaningful and must survive: it asks for a free bundle, where
            // omitting the field asks for the user's active project instead.
            body.put("projectId", destination.projectId() == null ? "" : destination.projectId());
        } else if (destination.bundleId() != null && !destination.bundleId().isBlank()) {
            body.put("bundleId", destination.bundleId());
        }
        body.put("requests", items);
        return body;
    }

    private String post(BridgeConfig config, String path, Object body) throws Exception {
        final byte[] json = mapper.writeValueAsBytes(body);
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
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

    private HttpRequest get(BridgeConfig config, String path, Duration timeout) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(timeout)
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
