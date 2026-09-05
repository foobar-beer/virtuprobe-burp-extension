package beer.foobar.virtuprobe.burp.config;

import burp.api.montoya.persistence.Preferences;

/**
 * Loads and saves the {@link BridgeConfig} in Burp's own persistence, so the connection settings
 * survive across Burp restarts. Missing values fall back to {@link BridgeConfig#defaults()}, which
 * points at a local desktop VirtuProbe, so the extension is usable before anything is configured.
 */
public class ConfigStore {

    private static final String HOST = "virtuprobe.host";
    private static final String PORT = "virtuprobe.port";
    private static final String TLS = "virtuprobe.tls";
    private static final String TOKEN = "virtuprobe.token";

    private final Preferences prefs;

    public ConfigStore(Preferences prefs) {
        this.prefs = prefs;
    }

    public BridgeConfig load() {
        final BridgeConfig defaults = BridgeConfig.defaults();
        final String host = orDefault(prefs.getString(HOST), defaults.host());
        final int port = parsePort(prefs.getString(PORT), defaults.port());
        final Boolean tls = prefs.getBoolean(TLS);
        final String token = orDefault(prefs.getString(TOKEN), defaults.token());
        return new BridgeConfig(host, port, tls != null ? tls : defaults.tls(), token);
    }

    public void save(BridgeConfig config) {
        prefs.setString(HOST, config.host());
        prefs.setString(PORT, String.valueOf(config.port()));
        prefs.setBoolean(TLS, config.tls());
        prefs.setString(TOKEN, config.token() == null ? "" : config.token());
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int parsePort(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
