package beer.foobar.virtuprobe.burp.model;

/**
 * The destination chosen in the Send to VirtuProbe dialog, remembered so the next send opens on it.
 *
 * <p>Jakub asked for the last choice to be the default, so this is persisted in Burp's own
 * {@code Preferences} and survives a Burp restart rather than living for the session. A stored id
 * can name a bundle or project that has since been deleted, so the dialog treats a remembered value
 * as a preference to re-select if it is still there and falls back quietly when it is not: refusing
 * to open on a stale id would make a deleted bundle block sending anything at all.
 *
 * @param projectId  the selected project, or null for "no project" (free bundles).
 * @param bundleId   the selected existing bundle, or null when a new one is being named.
 * @param verbatim   send the request byte for byte rather than parsed into fields.
 * @param promote    also create a regression chain asserting the status Burp observed.
 */
public record SendChoice(String projectId, String bundleId, boolean verbatim, boolean promote) {

    public static SendChoice defaults() {
        return new SendChoice(null, null, false, false);
    }
}
