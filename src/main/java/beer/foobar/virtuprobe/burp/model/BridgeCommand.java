package beer.foobar.virtuprobe.burp.model;

/**
 * One instruction VirtuProbe queued for this extension to carry out.
 *
 * <p><b>{@code type} is a String, not an enum mirrored from the server.</b> A newer VirtuProbe may
 * queue a destination this build has never heard of, and an enum would make that a parse failure
 * that takes down the whole poll loop, so every later command would be lost too. As a String an
 * unrecognised destination is one logged no-op, which is the same reasoning the proxy rule model
 * uses for its module-owned action keys.
 */
public record BridgeCommand(String id, String type, String rawBase64, String host, int port,
                            boolean tls, String tabName, long createdAt) {
}
