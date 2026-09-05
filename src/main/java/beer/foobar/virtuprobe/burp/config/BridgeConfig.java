package beer.foobar.virtuprobe.burp.config;

/**
 * Connection settings for the VirtuProbe front door.
 *
 * <p>The plugin always points at the local VirtuProbe server: the combined desktop app on
 * {@code :10100}, or the local UI server in the separated model. It authenticates with the API
 * token shown in VirtuProbe's Burp bridge settings panel. On a same machine loopback connection
 * VirtuProbe does not enforce the token, but the plugin sends it anyway so the same config works
 * against a separated execution server.
 */
public record BridgeConfig(String host, int port, boolean tls, String token) {

    public static BridgeConfig defaults() {
        return new BridgeConfig("127.0.0.1", 10100, false, "");
    }

    public String baseUrl() {
        return (tls ? "https://" : "http://") + host + ":" + port;
    }
}
