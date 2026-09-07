package beer.foobar.virtuprobe.burp.ui;

import beer.foobar.virtuprobe.burp.client.VirtuProbeClient;
import beer.foobar.virtuprobe.burp.config.BridgeConfig;
import beer.foobar.virtuprobe.burp.config.ConfigStore;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The VirtuProbe tab in Burp: where the connection to VirtuProbe is configured and tested.
 *
 * <p>The connection defaults to a local desktop VirtuProbe, so most installs need no change here.
 * A separated deployment, or one that moved the port, sets the host and port. Test connection checks
 * the bridge API rather than a plain health endpoint, so it reports whether the address serves the
 * bridge and whether the bridge is switched on.
 */
public class BridgeSettingsPanel extends JPanel {

    private static final Color OK = new Color(0x1b8a3a);
    private static final Color WARN = new Color(0xb8860b);
    private static final Color ERROR = new Color(0xc0392b);

    private final ConfigStore configStore;
    private final VirtuProbeClient client;

    private final JTextField hostField = new JTextField(18);
    private final JTextField portField = new JTextField(6);
    private final JCheckBox tlsBox = new JCheckBox("Use HTTPS");
    private final JTextField tokenField = new JTextField(28);
    private final JLabel statusLabel = new JLabel(" ");

    public BridgeSettingsPanel(ConfigStore configStore, VirtuProbeClient client) {
        this.configStore = configStore;
        this.client = client;
        buildLayout();
        loadFromConfig();
    }

    private void buildLayout() {
        setLayout(new BorderLayout());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addField(form, c, row++, "Host", hostField);
        addField(form, c, row++, "Port", portField);

        c.gridx = 1; c.gridy = row++; c.gridwidth = 1;
        form.add(tlsBox, c);

        addField(form, c, row++, "API token", tokenField);
        tokenField.setToolTipText("Only needed when VirtuProbe requires a token. Copy it from Settings, API access token.");

        c.gridx = 1; c.gridy = row++; c.gridwidth = 2;
        JPanel buttons = new JPanel();
        JButton save = new JButton("Save");
        save.addActionListener(e -> save());
        JButton test = new JButton("Test connection");
        test.addActionListener(e -> test());
        buttons.add(save);
        buttons.add(test);
        buttons.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        save.setMargin(new Insets(4, 12, 4, 12));
        form.add(buttons, c);

        c.gridx = 1; c.gridy = row; c.gridwidth = 2;
        form.add(statusLabel, c);

        add(form, BorderLayout.NORTH);
    }

    private void addField(JPanel form, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        form.add(field, c);
    }

    private void loadFromConfig() {
        BridgeConfig config = configStore.load();
        hostField.setText(config.host());
        portField.setText(String.valueOf(config.port()));
        tlsBox.setSelected(config.tls());
        tokenField.setText(config.token() == null ? "" : config.token());
    }

    private BridgeConfig currentConfig() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = tlsBox.isSelected() ? 443 : 10100;
        }
        return new BridgeConfig(hostField.getText().trim(), port, tlsBox.isSelected(), tokenField.getText().trim());
    }

    private void save() {
        configStore.save(currentConfig());
        setStatus("Saved.", OK);
    }

    private void test() {
        setStatus("Testing...", WARN);
        final BridgeConfig config = currentConfig();
        // Off the event thread: a network call on the EDT would freeze Burp's interface.
        new Thread(() -> {
            VirtuProbeClient.BridgeStatus status = client.checkBridge(config);
            Color color = status.enabled() ? OK : status.reachable() ? WARN : ERROR;
            SwingUtilities.invokeLater(() -> setStatus(status.message(), color));
        }, "vp-bridge-test").start();
    }

    private void setStatus(String message, Color color) {
        statusLabel.setText(message);
        statusLabel.setForeground(color);
    }
}
