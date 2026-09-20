package beer.foobar.virtuprobe.burp;

import beer.foobar.virtuprobe.burp.burp.CommandApplier;
import beer.foobar.virtuprobe.burp.burp.CommandPoller;
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
 * <p>Registered from here:
 * <ul>
 *   <li>a suite tab for host / port / token config, a connection test, and the poller's state;</li>
 *   <li>a context menu item that asks where a capture should land before importing it, singly or in
 *       bulk, optionally promoted to a regression test;</li>
 *   <li>a poller of {@code GET /burp/commands} that carries out Send to Burp from the VirtuProbe UI,
 *       into Repeater, Intruder, the Organizer or the site map.</li>
 * </ul>
 *
 * <p><b>The list above is the contract, so it has to stay true.</b> It previously claimed bulk send
 * and the poller were registered here while neither existed, which is a false statement in the one
 * place a reader trusts: the poller's absence is exactly why Send to Burp did nothing, and this
 * javadoc said it was wired.
 */
public class VirtuProbeBridgeExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("VirtuProbe Bridge");

        final ConfigStore configStore = new ConfigStore(api.persistence().preferences());
        final VirtuProbeClient client = new VirtuProbeClient();

        api.userInterface().registerContextMenuItemsProvider(
                new SendToVirtuProbeMenu(api, configStore, client));

        final BridgeSettingsPanel settings = new BridgeSettingsPanel(configStore, client);
        api.userInterface().registerSuiteTab("VirtuProbe", settings);

        final CommandPoller poller = new CommandPoller(client, configStore, new CommandApplier(api),
                api.logging(), settings::showPollState);
        settings.attachPoller(poller);
        if (configStore.isPollEnabled()) {
            poller.start();
        }
        // Without this the poller's thread outlives an unload and keeps polling a server the user
        // thinks it has disconnected from.
        api.extension().registerUnloadingHandler(poller::stop);

        api.logging().logToOutput("VirtuProbe Bridge loaded. Set the connection on the VirtuProbe tab, "
                + "then right click a request and choose Send to VirtuProbe. Default target is "
                + "http://127.0.0.1:10100.");
    }
}
