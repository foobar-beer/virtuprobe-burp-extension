package beer.foobar.virtuprobe.burp.client;

import beer.foobar.virtuprobe.burp.config.BridgeConfig;
import beer.foobar.virtuprobe.burp.model.BridgeCommand;
import beer.foobar.virtuprobe.burp.model.BridgeTargets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the extension actually puts on the wire, checked against a real HTTP server rather than a
 * mocked client.
 *
 * <p>The JSON bodies here are the contract with {@code BurpBridgeController}, and a mock would only
 * echo whatever the test believed. An unrecognised key is dropped in silence by the server, so a field
 * misspelled on this side produces a capture that imports with the wrong destination and no error
 * anywhere; these cases are the only thing standing between that and a shipped build.
 */
class VirtuProbeClientTest {

    private static final byte[] REQUEST =
            "GET /login HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] RESPONSE =
            "HTTP/1.1 302 Found\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final ObjectMapper mapper = new ObjectMapper();
    private final VirtuProbeClient client = new VirtuProbeClient();

    private HttpServer server;
    private BridgeConfig config;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        config = new BridgeConfig("127.0.0.1", server.getAddress().getPort(), false, "");
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Registers a handler that records what arrived and replies with the given JSON. */
    private void respond(String path, String json) {
        server.createContext(path, exchange -> {
            lastPath.set(exchange.getRequestURI().toString());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, json);
        });
    }

    private void reply(HttpExchange exchange, int status, String json) throws IOException {
        final byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private JsonNode sentBody() throws Exception {
        return mapper.readTree(lastBody.get());
    }

    private VirtuProbeClient.Capture capture() {
        return new VirtuProbeClient.Capture(REQUEST, RESPONSE, "example.test", 443, true);
    }

    // --- single import ------------------------------------------------------------------------

    @Test
    void importOneSendsTheRequestTheTargetAndTheChosenBundle() throws Exception {
        respond("/burp/request", "{\"probeId\":\"p-1\",\"bundleId\":\"b-1\"}");

        client.importOne(config, capture(), "b-1", false, false);

        JsonNode body = sentBody();
        assertEquals(Base64.getEncoder().encodeToString(REQUEST), body.get("rawBase64").asText());
        assertEquals("example.test", body.get("host").asText());
        assertEquals(443, body.get("port").asInt());
        assertTrue(body.get("tls").asBoolean());
        assertEquals("structured", body.get("mode").asText());
        assertEquals("b-1", body.get("bundleId").asText());
    }

    @Test
    void verbatimIsTheModeTheServerRecognises() throws Exception {
        respond("/burp/request", "{}");

        client.importOne(config, capture(), "b-1", true, false);

        assertEquals("verbatim", sentBody().get("mode").asText());
    }

    /**
     * A blank bundle id is omitted rather than sent empty, so the server falls back to its own default
     * instead of trying to resolve "" as a bundle.
     */
    @Test
    void noBundleMeansTheFieldIsAbsent() throws Exception {
        respond("/burp/request", "{}");

        client.importOne(config, capture(), "  ", false, false);

        assertFalse(sentBody().has("bundleId"));
    }

    /** Promotion needs the response: the assertion is seeded from the status Burp observed. */
    @Test
    void promotingSendsTheResponseSoAStatusCanBeAsserted() throws Exception {
        respond("/burp/request", "{\"chainId\":\"c-1\"}");

        client.importOne(config, capture(), "b-1", false, true);

        JsonNode body = sentBody();
        assertTrue(body.get("promote").asBoolean());
        assertEquals(Base64.getEncoder().encodeToString(RESPONSE), body.get("responseBase64").asText());
    }

    @Test
    void notPromotingSendsNeitherTheFlagNorTheResponse() throws Exception {
        respond("/burp/request", "{}");

        client.importOne(config, capture(), "b-1", false, false);

        JsonNode body = sentBody();
        assertFalse(body.has("promote"));
        assertFalse(body.has("responseBase64"), "a response nobody asked for is not worth the bytes");
    }

    @Test
    void promotingWithNoCapturedResponseStillImports() throws Exception {
        respond("/burp/request", "{}");

        client.importOne(config, new VirtuProbeClient.Capture(REQUEST, null, "example.test", 80, false),
                "b-1", false, true);

        JsonNode body = sentBody();
        assertTrue(body.get("promote").asBoolean());
        assertFalse(body.has("responseBase64"), "no response to send, and the server imports anyway");
    }

    // --- bulk import -------------------------------------------------------------------------

    @Test
    void importManySendsEveryRequestInOneCall() throws Exception {
        respond("/burp/requests", "{\"bundleId\":\"b-1\",\"imported\":2}");

        client.importMany(config, List.of(capture(), capture()),
                new VirtuProbeClient.Destination("b-1", null, null), false);

        JsonNode body = sentBody();
        assertEquals("/burp/requests", lastPath.get());
        assertEquals(2, body.get("requests").size());
        assertEquals("b-1", body.get("bundleId").asText());
        assertFalse(body.has("bundleName"));
    }

    /**
     * Choosing "(no project)" is a decision, so it travels as an EMPTY projectId rather than an absent
     * one. The server reads absent as "the user's active project" and empty as "a free bundle", and the
     * picker always knows which the user picked, so it must never leave the field out.
     */
    @Test
    void creatingABundleWithNoProjectSendsAnEmptyProjectIdNotAnAbsentOne() throws Exception {
        respond("/burp/requests", "{\"bundleId\":\"b-new\"}");

        client.importMany(config, List.of(capture()),
                new VirtuProbeClient.Destination(null, "From Burp", null), false);

        JsonNode body = sentBody();
        assertEquals("From Burp", body.get("bundleName").asText());
        assertTrue(body.has("projectId"), "absent would silently mean the active project");
        assertEquals("", body.get("projectId").asText());
        assertFalse(body.has("bundleId"));
    }

    @Test
    void creatingABundleInAProjectSendsThatProject() throws Exception {
        respond("/burp/requests", "{\"bundleId\":\"b-new\"}");

        client.importMany(config, List.of(capture()),
                new VirtuProbeClient.Destination(null, "From Burp", "p-acme"), false);

        assertEquals("p-acme", sentBody().get("projectId").asText());
    }

    @Test
    void aNewBundleNameWinsOverABundleId() throws Exception {
        respond("/burp/requests", "{\"bundleId\":\"b-new\"}");

        client.importMany(config, List.of(capture()),
                new VirtuProbeClient.Destination("b-old", "From Burp", null), false);

        JsonNode body = sentBody();
        assertEquals("From Burp", body.get("bundleName").asText());
        assertFalse(body.has("bundleId"), "sending both would let the server choose, not the user");
    }

    /** The picker's create path: name a bundle, send nothing into it yet, learn the id. */
    @Test
    void createBundleSendsAnEmptyRequestListAndReturnsTheNewId() throws Exception {
        respond("/burp/requests", "{\"bundleId\":\"b-new\",\"bundleName\":\"From Burp\",\"imported\":0}");

        String id = client.createBundle(config, "From Burp", "p-acme");

        assertEquals("b-new", id);
        JsonNode body = sentBody();
        assertEquals(0, body.get("requests").size());
        assertEquals("From Burp", body.get("bundleName").asText());
    }

    @Test
    void createBundleFailsLoudlyWhenNoBundleComesBack() {
        server.createContext("/burp/requests", exchange -> reply(exchange, 200, "{\"imported\":0}"));

        assertThrows(IllegalStateException.class, () -> client.createBundle(config, "From Burp", null));
    }

    // --- targets -----------------------------------------------------------------------------

    @Test
    void targetsParsesBundlesAndProjects() throws Exception {
        respond("/burp/targets", """
                {"bundles":[{"id":"b-1","name":"Login flow","projectIds":["p-1"]},
                            {"id":"b-2","name":"Free","projectIds":[]}],
                 "projects":[{"id":"p-1","name":"Acme"}]}""");

        BridgeTargets targets = client.targets(config);

        assertEquals(2, targets.safeBundles().size());
        assertEquals("Login flow", targets.safeBundles().get(0).name());
        assertEquals(List.of("p-1"), targets.safeBundles().get(0).projectIds());
        assertTrue(targets.safeBundles().get(1).isFree());
        assertEquals("Acme", targets.safeProjects().get(0).name());
    }

    /**
     * An installed extension outlives the VirtuProbe it was built against, so a field this build has
     * never heard of must not break loading the destination list. Failing closed here would leave the
     * user unable to send anything after a server upgrade.
     */
    @Test
    void targetsToleratesFieldsThisBuildDoesNotKnow() throws Exception {
        respond("/burp/targets", """
                {"bundles":[{"id":"b-1","name":"Login","projectIds":[],"parentBundleId":"b-0",
                             "somethingAddedLater":{"nested":true}}],
                 "projects":[{"id":"p-1","name":"Acme","defaultCredentialId":"c-1"}],
                 "wholeNewSection":[1,2,3]}""");

        BridgeTargets targets = client.targets(config);

        assertEquals("b-1", targets.safeBundles().get(0).id());
        assertEquals("Acme", targets.safeProjects().get(0).name());
    }

    @Test
    void targetsFailsWhenTheServerRefuses() {
        server.createContext("/burp/targets", exchange -> reply(exchange, 403, "{\"error\":\"off\"}"));

        assertThrows(IllegalStateException.class, () -> client.targets(config));
    }

    // --- command polling ---------------------------------------------------------------------

    @Test
    void pollCommandsParsesTheQueueAndAsksTheServerToWait() throws Exception {
        respond("/burp/commands", """
                [{"id":"c-1","type":"SEND_TO_REPEATER","rawBase64":"cmVx","host":"example.test",
                  "port":443,"tls":true,"tabName":"Login","createdAt":123}]""");

        List<BridgeCommand> commands = client.pollCommands(config, 25);

        assertEquals(1, commands.size());
        BridgeCommand command = commands.get(0);
        assertEquals("SEND_TO_REPEATER", command.type());
        assertEquals("Login", command.tabName());
        assertEquals("example.test", command.host());
        assertEquals(443, command.port());
        assertTrue(command.tls());
        assertTrue(lastPath.get().contains("wait=25"), "actual: " + lastPath.get());
    }

    @Test
    void anEmptyQueueIsNotAnError() throws Exception {
        respond("/burp/commands", "[]");

        assertTrue(client.pollCommands(config, 0).isEmpty());
    }

    /**
     * A destination this build does not know must survive PARSING, so it can be reported as one
     * skipped command. As an enum it would fail the whole response and lose every command beside it.
     */
    @Test
    void aDestinationThisBuildDoesNotKnowStillParses() throws Exception {
        respond("/burp/commands", """
                [{"id":"c-1","type":"SEND_TO_SOMETHING_NEW","rawBase64":"cmVx","host":"h",
                  "port":80,"tls":false,"tabName":null,"createdAt":1,"newFieldToo":"x"}]""");

        List<BridgeCommand> commands = client.pollCommands(config, 0);

        assertEquals("SEND_TO_SOMETHING_NEW", commands.get(0).type());
        assertNull(commands.get(0).tabName());
    }

    // --- authentication ----------------------------------------------------------------------

    @Test
    void aConfiguredTokenIsSentAsABearerHeader() throws Exception {
        respond("/burp/targets", "{\"bundles\":[],\"projects\":[]}");
        BridgeConfig withToken = new BridgeConfig(config.host(), config.port(), false, "secret-token");

        client.targets(withToken);

        assertEquals("Bearer secret-token", lastAuth.get());
    }

    /** A loopback desktop VirtuProbe needs no token, so an empty one must not become "Bearer ". */
    @Test
    void noTokenMeansNoHeaderAtAll() throws Exception {
        respond("/burp/targets", "{\"bundles\":[],\"projects\":[]}");

        client.targets(config);

        assertNull(lastAuth.get());
    }

    @Test
    void aRefusedImportCarriesTheServersOwnMessage() {
        server.createContext("/burp/request",
                exchange -> reply(exchange, 400, "{\"message\":\"rawBase64 is not valid base64.\"}"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> client.importOne(config, capture(), "b-1", false, false));

        assertTrue(thrown.getMessage().contains("rawBase64 is not valid base64."),
                "the server's reason reaches the log rather than being replaced: " + thrown.getMessage());
    }
}
