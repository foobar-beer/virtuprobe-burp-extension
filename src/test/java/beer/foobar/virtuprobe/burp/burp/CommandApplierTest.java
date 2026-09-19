package beer.foobar.virtuprobe.burp.burp;

import beer.foobar.virtuprobe.burp.model.BridgeCommand;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.intruder.Intruder;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.organizer.Organizer;
import burp.api.montoya.repeater.Repeater;
import burp.api.montoya.sitemap.SiteMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Where a queued request actually lands, which is the whole point of letting the user choose.
 *
 * <p>Each case asserts the ONE Montoya call that destination should make and that the others were not
 * made, because "it went somewhere in Burp" is what the picker exists to stop being the answer.
 */
class CommandApplierTest {

    private static final String RAW = "GET /login HTTP/1.1\r\nHost: example.test\r\n\r\n";

    private MontoyaApi api;
    private Repeater repeater;
    private Intruder intruder;
    private Organizer organizer;
    private SiteMap siteMap;
    private Http http;
    private Logging logging;
    private CommandApplier applier;

    @BeforeAll
    static void installMontoyaFactory() {
        MontoyaFactoryStub.install();
    }

    @BeforeEach
    void setUp() {
        api = mock(MontoyaApi.class);
        repeater = mock(Repeater.class);
        intruder = mock(Intruder.class);
        organizer = mock(Organizer.class);
        siteMap = mock(SiteMap.class);
        http = mock(Http.class);
        logging = mock(Logging.class);
        when(api.repeater()).thenReturn(repeater);
        when(api.intruder()).thenReturn(intruder);
        when(api.organizer()).thenReturn(organizer);
        when(api.siteMap()).thenReturn(siteMap);
        when(api.http()).thenReturn(http);
        when(api.logging()).thenReturn(logging);
        applier = new CommandApplier(api);
    }

    private BridgeCommand command(String type, String tabName) {
        return new BridgeCommand("cmd-1", type,
                Base64.getEncoder().encodeToString(RAW.getBytes(StandardCharsets.ISO_8859_1)),
                "example.test", 443, true, tabName, System.currentTimeMillis());
    }

    @Test
    void repeaterGetsTheRequestAndTheTabName() {
        assertTrue(applier.apply(command("SEND_TO_REPEATER", "Login")));

        verify(repeater).sendToRepeater(any(HttpRequest.class), eqTab("Login"));
        verifyNoInteractions(intruder, organizer, siteMap);
    }

    @Test
    void intruderGetsTheRequestAndTheTabName() {
        assertTrue(applier.apply(command("SEND_TO_INTRUDER", "Fuzz login")));

        verify(intruder).sendToIntruder(any(HttpRequest.class), eqTab("Fuzz login"));
        verifyNoInteractions(repeater, organizer, siteMap);
    }

    @Test
    void organizerTakesTheRequestAlone() {
        assertTrue(applier.apply(command("SEND_TO_ORGANIZER", "ignored")));

        verify(organizer).sendToOrganizer(any(HttpRequest.class));
        verifyNoInteractions(repeater, intruder, siteMap);
    }

    /**
     * The site map is the only destination needing a response, and the extension must NOT obtain one
     * by issuing the request: a filing action that also puts traffic on the target is a surprise a
     * tester cannot afford. So an empty response is supplied and {@code api.http()} stays untouched.
     */
    @Test
    void siteMapGetsAnEmptyResponseAndNoRequestIsEverIssued() {
        assertTrue(applier.apply(command("SEND_TO_SITE_MAP", null)));

        ArgumentCaptor<HttpRequestResponse> captor = ArgumentCaptor.forClass(HttpRequestResponse.class);
        verify(siteMap).add(captor.capture());
        assertNotNull(captor.getValue().response(), "the site map entry carries a response object");
        verifyNoInteractions(http);
        verifyNoInteractions(repeater, intruder, organizer);
    }

    /** A blank tab name uses the overload without one, rather than naming a tab "" or "null". */
    @Test
    void aBlankTabNameUsesTheUnnamedOverload() {
        assertTrue(applier.apply(command("SEND_TO_REPEATER", "   ")));

        verify(repeater).sendToRepeater(any(HttpRequest.class));
        verify(repeater, never()).sendToRepeater(any(HttpRequest.class), anyString());
    }

    @Test
    void theDestinationNameIsCaseInsensitive() {
        assertTrue(applier.apply(command("send_to_repeater", "Login")));

        verify(repeater).sendToRepeater(any(HttpRequest.class), eqTab("Login"));
    }

    /**
     * A newer VirtuProbe can queue a destination this build has never heard of. Sending it somewhere
     * else would put the request in a place the user did not choose, so it is a logged no-op.
     */
    @Test
    void anUnknownDestinationSendsNothingAnywhereAndSaysSo() {
        assertFalse(applier.apply(command("SEND_TO_SOMETHING_NEW", "Login")));

        verifyNoInteractions(repeater, intruder, organizer, siteMap, http);
        verify(logging).logToError(anyString());
    }

    @Test
    void aCommandWithNoRequestIsDroppedRatherThanApplied() {
        BridgeCommand empty = new BridgeCommand("cmd-2", "SEND_TO_REPEATER", null,
                "example.test", 443, true, "Login", 0L);

        assertFalse(applier.apply(empty));

        verifyNoInteractions(repeater, intruder, organizer, siteMap);
        verify(logging).logToError(anyString());
    }

    @Test
    void aCommandWhoseRequestIsNotBase64IsDroppedRatherThanApplied() {
        BridgeCommand bad = new BridgeCommand("cmd-3", "SEND_TO_REPEATER", "!!!not base64!!!",
                "example.test", 443, true, "Login", 0L);

        assertFalse(applier.apply(bad));

        verifyNoInteractions(repeater, intruder, organizer, siteMap);
        verify(logging).logToError(anyString());
    }

    /**
     * Nothing may escape apply: this runs inside the poll loop, and one exception would end the loop
     * and lose every later command, so a destination that throws is contained here.
     */
    @Test
    void anExceptionFromBurpIsContainedRatherThanThrown() {
        when(api.repeater()).thenThrow(new IllegalStateException("Burp said no"));

        assertFalse(applier.apply(command("SEND_TO_REPEATER", "Login")));

        verify(logging).logToError(anyString());
    }

    @Test
    void theTargetHostPortAndTlsReachTheRequest() {
        applier.apply(command("SEND_TO_REPEATER", "Login"));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(repeater).sendToRepeater(captor.capture(), anyString());
        assertEquals("example.test", captor.getValue().httpService().host());
        assertEquals(443, captor.getValue().httpService().port());
        assertTrue(captor.getValue().httpService().secure());
    }

    private static String eqTab(String expected) {
        return org.mockito.ArgumentMatchers.eq(expected);
    }
}
