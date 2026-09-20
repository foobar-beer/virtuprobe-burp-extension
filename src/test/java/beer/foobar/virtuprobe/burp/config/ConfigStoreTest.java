package beer.foobar.virtuprobe.burp.config;

import beer.foobar.virtuprobe.burp.model.SendChoice;
import burp.api.montoya.persistence.Preferences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remembered destination, which Jakub asked to survive across sends.
 *
 * <p>Backed by {@link FakePreferences} rather than a mock: a store is exactly the thing a mock cannot
 * test, because a mock returns whatever the test says and would pass with the save method empty.
 */
class ConfigStoreTest {

    private final FakePreferences prefs = new FakePreferences();
    private final ConfigStore store = new ConfigStore(prefs);

    @Test
    void theLastChoiceRoundTrips() {
        store.saveLastChoice(new SendChoice("p-1", "b-1", true, true));

        SendChoice loaded = store.loadLastChoice();

        assertEquals("p-1", loaded.projectId());
        assertEquals("b-1", loaded.bundleId());
        assertTrue(loaded.verbatim());
        assertTrue(loaded.promote());
    }

    /**
     * Nothing stored yet has to read as "no preference", not as a project called empty string. The
     * store writes empty strings because Preferences cannot unset one, so the two have to converge.
     */
    @Test
    void anUnsetChoiceReadsAsNoPreference() {
        SendChoice loaded = store.loadLastChoice();

        assertNull(loaded.projectId());
        assertNull(loaded.bundleId());
        assertFalse(loaded.verbatim());
        assertFalse(loaded.promote());
    }

    @Test
    void chosenNoProjectRoundTripsToNullRatherThanAnEmptyId() {
        store.saveLastChoice(new SendChoice(null, "b-free", false, false));

        assertNull(store.loadLastChoice().projectId());
        assertEquals("b-free", store.loadLastChoice().bundleId());
    }

    @Test
    void aLaterChoiceReplacesTheEarlierOne() {
        store.saveLastChoice(new SendChoice("p-1", "b-1", true, true));
        store.saveLastChoice(new SendChoice("p-2", "b-2", false, false));

        SendChoice loaded = store.loadLastChoice();

        assertEquals("p-2", loaded.projectId());
        assertEquals("b-2", loaded.bundleId());
        assertFalse(loaded.verbatim());
        assertFalse(loaded.promote());
    }

    /**
     * Polling defaults ON for a fresh install. The bridge already has an opt-in toggle on the
     * VirtuProbe side, so defaulting off here would mean a user who enabled it there still gets
     * nothing, which is the silence this whole direction had to fix.
     */
    @Test
    void pollingIsOnUntilTurnedOff() {
        assertTrue(store.isPollEnabled());

        store.setPollEnabled(false);
        assertFalse(store.isPollEnabled());

        store.setPollEnabled(true);
        assertTrue(store.isPollEnabled());
    }

    @Test
    void connectionSettingsDefaultToALocalDesktopVirtuProbe() {
        BridgeConfig config = store.load();

        assertEquals("127.0.0.1", config.host());
        assertEquals(10100, config.port());
        assertFalse(config.tls());
        assertEquals("http://127.0.0.1:10100", config.baseUrl());
    }

    @Test
    void aGarbagePortFallsBackRatherThanThrowing() {
        prefs.setString("virtuprobe.port", "not-a-number");

        assertEquals(10100, store.load().port());
    }
}
