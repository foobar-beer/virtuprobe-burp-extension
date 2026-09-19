package beer.foobar.virtuprobe.burp.menu;

import beer.foobar.virtuprobe.burp.client.VirtuProbeClient;
import beer.foobar.virtuprobe.burp.config.BridgeConfig;
import beer.foobar.virtuprobe.burp.config.ConfigStore;
import beer.foobar.virtuprobe.burp.model.BridgeTargets;
import beer.foobar.virtuprobe.burp.model.SendChoice;
import beer.foobar.virtuprobe.burp.ui.SendToVirtuProbeDialog;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adds Send to VirtuProbe to the right click menu on any HTTP request (Proxy history, site map,
 * Repeater, the message editor).
 *
 * <p>One menu item, and it always asks where the capture should land. Mode and promotion moved into
 * the dialog, so there is no longer a separate verbatim item: three menu entries that differed only
 * in a flag were harder to read than one that shows the options.
 *
 * <p><b>Nothing here touches the network on the event thread.</b> The destination list is fetched on a
 * worker, the dialog is shown on the event thread, and the send goes back to a worker. Doing any of it
 * the other way round freezes Burp.
 */
public class SendToVirtuProbeMenu implements ContextMenuItemsProvider {

    private final MontoyaApi api;
    private final ConfigStore configStore;
    private final VirtuProbeClient client;
    private final Logging logging;

    public SendToVirtuProbeMenu(MontoyaApi api, ConfigStore configStore, VirtuProbeClient client) {
        this.api = api;
        this.configStore = configStore;
        this.client = client;
        this.logging = api.logging();
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        final List<HttpRequestResponse> selected = selected(event);
        if (selected.isEmpty()) {
            return List.of();
        }
        final JMenuItem item = new JMenuItem(selected.size() == 1
                ? "Send to VirtuProbe..."
                : "Send " + selected.size() + " requests to VirtuProbe...");
        item.addActionListener(e -> begin(selected));
        return List.of(item);
    }

    private List<HttpRequestResponse> selected(ContextMenuEvent event) {
        if (!event.selectedRequestResponses().isEmpty()) {
            return event.selectedRequestResponses();
        }
        return event.messageEditorRequestResponse()
                .map(editor -> List.of(editor.requestResponse()))
                .orElse(List.of());
    }

    /** Reads the selection now, on the event thread, then hands the rest to a worker. */
    private void begin(List<HttpRequestResponse> selected) {
        final List<VirtuProbeClient.Capture> captures = toCaptures(selected);
        if (captures.isEmpty()) {
            logging.logToError("Send to VirtuProbe: nothing in the selection carried a request.");
            return;
        }
        final BridgeConfig config = configStore.load();
        new Thread(() -> {
            BridgeTargets targets = BridgeTargets.empty();
            String loadError = null;
            try {
                targets = client.targets(config);
            } catch (Exception e) {
                loadError = "Could not load destinations from " + config.baseUrl()
                        + ". Check the VirtuProbe tab.";
                logging.logToError("Send to VirtuProbe could not load destinations: " + e);
            }
            final BridgeTargets loaded = targets;
            final String error = loadError;
            SwingUtilities.invokeLater(() -> prompt(config, captures, loaded, error));
        }, "vp-load-targets").start();
    }

    /** On the event thread: ask, then send on a worker. */
    private void prompt(BridgeConfig config, List<VirtuProbeClient.Capture> captures,
                        BridgeTargets targets, String loadError) {
        final Optional<SendToVirtuProbeDialog.Result> answer = SendToVirtuProbeDialog.ask(
                api.userInterface().swingUtils().suiteFrame(), targets, captures.size(),
                configStore.loadLastChoice(), loadError);
        if (answer.isEmpty()) {
            return;
        }
        final SendToVirtuProbeDialog.Result result = answer.get();
        new Thread(() -> send(config, captures, result), "vp-send-to-virtuprobe").start();
    }

    private void send(BridgeConfig config, List<VirtuProbeClient.Capture> captures,
                      SendToVirtuProbeDialog.Result result) {
        final SendChoice choice = result.choice();
        try {
            final String bundleId = result.createsBundle()
                    ? client.createBundle(config, result.newBundleName(), choice.projectId())
                    : choice.bundleId();

            if (choice.promote() && captures.size() == 1) {
                // Promotion is per capture and only the single endpoint offers it, which is also why
                // a new bundle had to be created first: the bulk endpoint names bundles, the single
                // one only takes an id.
                client.importOne(config, captures.get(0), bundleId, choice.verbatim(), true);
            } else {
                client.importMany(config, captures,
                        new VirtuProbeClient.Destination(bundleId, null, choice.projectId()),
                        choice.verbatim());
            }

            // Remembered only on success, and with the bundle that was actually used, so a bundle
            // created just now becomes the default for the next send rather than reopening on "+ New".
            configStore.saveLastChoice(new SendChoice(choice.projectId(), bundleId,
                    choice.verbatim(), choice.promote()));
            logging.logToOutput("Sent " + captures.size() + " request(s) to VirtuProbe.");
        } catch (Exception e) {
            logging.logToError("Send to VirtuProbe failed: " + e.getMessage());
        }
    }

    private List<VirtuProbeClient.Capture> toCaptures(List<HttpRequestResponse> selected) {
        final List<VirtuProbeClient.Capture> captures = new ArrayList<>();
        for (HttpRequestResponse rr : selected) {
            if (rr.request() == null) {
                continue;
            }
            final HttpService service = rr.httpService();
            if (service == null) {
                // Without a service there is no host, and the server needs one: a request in
                // origin-form carries only a path. Skipping is honest; guessing port 80 was not.
                logging.logToError("Send to VirtuProbe skipped a request with no target service.");
                continue;
            }
            captures.add(new VirtuProbeClient.Capture(
                    rr.request().toByteArray().getBytes(),
                    rr.response() == null ? null : rr.response().toByteArray().getBytes(),
                    service.host(), service.port(), service.secure()));
        }
        return captures;
    }
}
