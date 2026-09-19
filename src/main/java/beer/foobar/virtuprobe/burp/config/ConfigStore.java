package beer.foobar.virtuprobe.burp.config;

import beer.foobar.virtuprobe.burp.model.SendChoice;
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
    private static final String LAST_PROJECT = "virtuprobe.last.projectId";
    private static final String LAST_BUNDLE = "virtuprobe.last.bundleId";
    private static final String LAST_VERBATIM = "virtuprobe.last.verbatim";
    private static final String LAST_PROMOTE = "virtuprobe.last.promote";
    private static final String POLL_ENABLED = "virtuprobe.poll.enabled";

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

    /**
     * The destination chosen on the last send, so the next one opens on it. Jakub asked for this
     * explicitly, and it lives in Burp's {@code Preferences} rather than in memory so it survives a
     * Burp restart.
     *
     * <p>Absent values come back as nulls, which the dialog reads as "no preference yet". A stored id
     * naming something since deleted is not repaired here: the dialog re-selects it only if it is
     * still in the target list, because this store cannot know what the workspace holds.
     */
    public SendChoice loadLastChoice() {
        return new SendChoice(
                emptyToNull(prefs.getString(LAST_PROJECT)),
                emptyToNull(prefs.getString(LAST_BUNDLE)),
                Boolean.TRUE.equals(prefs.getBoolean(LAST_VERBATIM)),
                Boolean.TRUE.equals(prefs.getBoolean(LAST_PROMOTE)));
    }

    public void saveLastChoice(SendChoice choice) {
        // Written as an empty string rather than left unset: "the user chose no project" and "nothing
        // stored yet" have to round-trip to the same null, and Preferences has no delete for a string.
        prefs.setString(LAST_PROJECT, choice.projectId() == null ? "" : choice.projectId());
        prefs.setString(LAST_BUNDLE, choice.bundleId() == null ? "" : choice.bundleId());
        prefs.setBoolean(LAST_VERBATIM, choice.verbatim());
        prefs.setBoolean(LAST_PROMOTE, choice.promote());
    }

    /**
     * Whether to poll VirtuProbe for Send to Burp commands. On by default: the bridge already has an
     * opt-in toggle on the VirtuProbe side, and an extension that is loaded and configured but quietly
     * not listening is the failure this whole direction had to begin with.
     */
    public boolean isPollEnabled() {
        final Boolean stored = prefs.getBoolean(POLL_ENABLED);
        return stored == null || stored;
    }

    public void setPollEnabled(boolean enabled) {
        prefs.setBoolean(POLL_ENABLED, enabled);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
