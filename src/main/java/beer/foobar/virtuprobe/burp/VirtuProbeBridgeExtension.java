package beer.foobar.virtuprobe.burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/**
 * Entry point for the VirtuProbe Bridge Burp extension. Burp discovers this class because it
 * implements {@link BurpExtension} and calls {@link #initialize(MontoyaApi)} once at load.
 *
 * <p>The extension is a thin client. It talks to the local VirtuProbe front door (the combined
 * desktop app on :10100, or the local UI server in the separated model) over the token-gated
 * {@code /burp/*} API. No testing logic lives here; VirtuProbe owns the workspace, the runs and
 * the gating.
 *
 * <p>Slice 1 (round-trip) wiring is registered from here:
 * <ul>
 *   <li>a suite tab for host / port / token config and a connection test;</li>
 *   <li>context menu items: Send to VirtuProbe (structured), (verbatim), and (as test);</li>
 *   <li>bulk send from Proxy history and the site map;</li>
 *   <li>a background poller of {@code GET /burp/commands} that drives Send to Repeater / Intruder
 *       from the VirtuProbe UI.</li>
 * </ul>
 */
public class VirtuProbeBridgeExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("VirtuProbe Bridge");
        api.logging().logToOutput(
                "VirtuProbe Bridge loaded. Set the VirtuProbe host, port and token in the "
                        + "VirtuProbe tab, then use the right click menu to send requests.");
        // TODO(slice 1): register the config tab, context menu and command poller here.
    }
}
