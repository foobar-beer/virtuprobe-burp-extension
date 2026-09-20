package beer.foobar.virtuprobe.burp.burp;

import beer.foobar.virtuprobe.burp.model.BridgeCommand;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;

import java.util.Base64;
import java.util.Locale;

/**
 * Puts a request VirtuProbe handed back where the user asked for it in Burp.
 *
 * <p><b>Nothing here may throw.</b> This runs inside the poll loop, and an exception escaping one
 * command would take down the loop and lose every command after it, so a malformed command is logged
 * and dropped. That is the same rule the MCP lens follows: a component that observes or relays must
 * not be able to break the thing it serves.
 *
 * <p><b>An unrecognised destination is a logged no-op, never a guess.</b> A newer VirtuProbe can queue
 * a destination this build does not know, and quietly sending it somewhere else would put a request
 * in a place the user did not choose.
 */
public class CommandApplier {

    private final MontoyaApi api;
    private final Logging logging;

    public CommandApplier(MontoyaApi api) {
        this.api = api;
        this.logging = api.logging();
    }

    /** @return true when the command was applied, false when it was dropped. */
    public boolean apply(BridgeCommand command) {
        try {
            final byte[] raw = decode(command);
            if (raw == null) {
                return false;
            }
            final HttpService service = HttpService.httpService(
                    command.host() == null ? "" : command.host(), command.port(), command.tls());
            final HttpRequest request = HttpRequest.httpRequest(service, ByteArray.byteArray(raw));
            return route(command, request);
        } catch (Exception e) {
            // Deliberately broad: see the class note. A command that cannot be applied is worth a log
            // line, never the loop.
            logging.logToError("VirtuProbe: could not apply a " + command.type() + " command: " + e);
            return false;
        }
    }

    private boolean route(BridgeCommand command, HttpRequest request) {
        final String type = command.type() == null ? "" : command.type().trim().toUpperCase(Locale.ROOT);
        final String tab = blankToNull(command.tabName());
        switch (type) {
            case "SEND_TO_REPEATER" -> {
                if (tab == null) {
                    api.repeater().sendToRepeater(request);
                } else {
                    api.repeater().sendToRepeater(request, tab);
                }
            }
            case "SEND_TO_INTRUDER" -> {
                if (tab == null) {
                    api.intruder().sendToIntruder(request);
                } else {
                    api.intruder().sendToIntruder(request, tab);
                }
            }
            case "SEND_TO_ORGANIZER" -> api.organizer().sendToOrganizer(request);
            case "SEND_TO_SITE_MAP" -> api.siteMap().add(withEmptyResponse(request));
            default -> {
                logging.logToError("VirtuProbe queued a destination this extension does not know: "
                        + command.type() + ". Nothing was sent. Update the extension.");
                return false;
            }
        }
        logging.logToOutput("VirtuProbe: " + type + (tab == null ? "" : " as \"" + tab + "\"") + ".");
        return true;
    }

    /**
     * A site map entry with an empty response, because {@code SiteMap.add} takes a request AND a
     * response while every other destination takes a request alone.
     *
     * <p><b>The extension must not issue the request to obtain a real response.</b> A menu item that
     * says "add to the site map" would then also put traffic on the target, which is a surprising
     * side effect from a filing action and exactly the kind of thing a tester has to be able to trust.
     * An empty response still registers the URL, which is what the site map is for.
     */
    private HttpRequestResponse withEmptyResponse(HttpRequest request) {
        return HttpRequestResponse.httpRequestResponse(request, HttpResponse.httpResponse());
    }

    private byte[] decode(BridgeCommand command) {
        if (command.rawBase64() == null || command.rawBase64().isBlank()) {
            logging.logToError("VirtuProbe queued a command with no request in it. Nothing was sent.");
            return null;
        }
        try {
            return Base64.getDecoder().decode(command.rawBase64().trim());
        } catch (IllegalArgumentException e) {
            logging.logToError("VirtuProbe queued a command whose request was not valid base64. "
                    + "Nothing was sent.");
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
