package beer.foobar.virtuprobe.burp.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which bundles the picker offers for a given project.
 *
 * <p>This mirrors VirtuProbe's own visibility rule (a bundle is visible when it is free or belongs to
 * the active project), so the cases are here to keep the two ends from drifting: a picker that offers
 * a bundle the app would not show is a capture the user cannot find afterwards.
 */
class BridgeTargetsTest {

    private static BridgeTargets.Bundle bundle(String id, String... projectIds) {
        return new BridgeTargets.Bundle(id, "name-" + id, List.of(projectIds));
    }

    private final BridgeTargets targets = new BridgeTargets(
            List.of(bundle("free"), bundle("acme", "p-acme"), bundle("other", "p-other"),
                    bundle("shared", "p-acme", "p-other")),
            List.of(new BridgeTargets.Project("p-acme", "Acme"),
                    new BridgeTargets.Project("p-other", "Other")));

    @Test
    void aProjectSeesItsOwnBundlesPlusTheFreeOnes() {
        List<String> ids = targets.bundlesFor("p-acme").stream().map(BridgeTargets.Bundle::id).toList();

        assertEquals(List.of("free", "acme", "shared"), ids);
    }

    @Test
    void aProjectDoesNotSeeAnotherProjectsBundles() {
        assertFalse(targets.bundlesFor("p-acme").stream()
                .anyMatch(b -> "other".equals(b.id())));
    }

    /** "No project" is not "everything": it is the bundles that belong nowhere. */
    @Test
    void noProjectSeesOnlyFreeBundles() {
        List<String> ids = targets.bundlesFor(null).stream().map(BridgeTargets.Bundle::id).toList();

        assertEquals(List.of("free"), ids);
    }

    @Test
    void anUnknownProjectStillSeesTheFreeBundles() {
        List<String> ids = targets.bundlesFor("p-deleted").stream().map(BridgeTargets.Bundle::id).toList();

        assertEquals(List.of("free"), ids);
    }

    /** A null projectIds is what the wire sends for a free bundle, and must not be read as "scoped". */
    @Test
    void nullProjectIdsCountsAsFree() {
        BridgeTargets withNull = new BridgeTargets(
                List.of(new BridgeTargets.Bundle("b", "B", null)), List.of());

        assertTrue(withNull.safeBundles().get(0).isFree());
        assertEquals(1, withNull.bundlesFor("anything").size());
        assertEquals(1, withNull.bundlesFor(null).size());
    }

    @Test
    void nullListsDoNotThrow() {
        BridgeTargets nulls = new BridgeTargets(null, null);

        assertTrue(nulls.safeBundles().isEmpty());
        assertTrue(nulls.safeProjects().isEmpty());
        assertTrue(nulls.bundlesFor("p").isEmpty());
    }
}
