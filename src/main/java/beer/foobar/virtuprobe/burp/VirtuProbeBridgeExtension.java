package beer.foobar.virtuprobe.burp;

import beer.foobar.virtuprobe.burp.client.VirtuProbeClient;
import beer.foobar.virtuprobe.burp.config.ConfigStore;
import beer.foobar.virtuprobe.burp.menu.SendToVirtuProbeMenu;
import beer.foobar.virtuprobe.burp.ui.BridgeSettingsPanel;
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

        final ConfigStore configStore = new ConfigStore(api.persistence().preferences());
        final VirtuProbeClient client = new VirtuProbeClient();
        api.userInterface().registerContextMenuItemsProvider(
                new SendToVirtuProbeMenu(api, configStore, client));
        api.userInterface().registerSuiteTab("VirtuProbe", new BridgeSettingsPanel(configStore, client));

        api.logging().logToOutput("VirtuProbe Bridge loaded. Set the connection on the VirtuProbe tab, "
                + "then right click a request and choose Send to VirtuProbe. Default target is "
                + "http://127.0.0.1:10100.");
        // Still to come: bulk send, and a poller for the VirtuProbe to Burp direction (Send to Repeater).
    }
}
