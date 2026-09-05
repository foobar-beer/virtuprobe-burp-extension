package beer.foobar.virtuprobe.burp.menu;

import beer.foobar.virtuprobe.burp.client.VirtuProbeClient;
import beer.foobar.virtuprobe.burp.config.BridgeConfig;
import beer.foobar.virtuprobe.burp.config.ConfigStore;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds Send to VirtuProbe to the right click menu on any HTTP request (Proxy history, site map,
 * Repeater, the message editor). Each selected request is imported as an HTTP probe.
 *
 * <p>The send runs off the Swing event thread: a network call on the EDT would freeze Burp. Failures
 * are logged rather than shown as a dialog, because a dialog blocks the extension.
 */
public class SendToVirtuProbeMenu implements ContextMenuItemsProvider {

    private final ConfigStore configStore;
    private final VirtuProbeClient client;
    private final Logging logging;

    public SendToVirtuProbeMenu(MontoyaApi api, ConfigStore configStore, VirtuProbeClient client) {
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
        final List<Component> items = new ArrayList<>();
        items.add(menuItem("Send to VirtuProbe", selected, false));
        items.add(menuItem("Send to VirtuProbe (verbatim)", selected, true));
        return items;
    }

    private List<HttpRequestResponse> selected(ContextMenuEvent event) {
        if (!event.selectedRequestResponses().isEmpty()) {
            return event.selectedRequestResponses();
        }
        return event.messageEditorRequestResponse()
                .map(editor -> List.of(editor.requestResponse()))
                .orElse(List.of());
    }

    private JMenuItem menuItem(String label, List<HttpRequestResponse> selected, boolean verbatim) {
        final JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> send(selected, verbatim));
        return item;
    }

    private void send(List<HttpRequestResponse> selected, boolean verbatim) {
        final BridgeConfig config = configStore.load();
        new Thread(() -> {
            int sent = 0;
            for (HttpRequestResponse rr : selected) {
                if (rr.request() == null) {
                    continue;
                }
                try {
                    final HttpService service = rr.httpService();
                    final String host = service != null ? service.host() : "";
                    final int port = service != null ? service.port() : (service != null && service.secure() ? 443 : 80);
                    final boolean tls = service != null && service.secure();
                    client.importRequest(config, rr.request().toByteArray().getBytes(), host, port, tls, verbatim);
                    sent++;
                } catch (Exception ex) {
                    logging.logToError("Send to VirtuProbe failed for one request: " + ex.getMessage());
                }
            }
            logging.logToOutput("Sent " + sent + " of " + selected.size() + " request(s) to VirtuProbe.");
        }, "vp-send-to-virtuprobe").start();
    }
}
