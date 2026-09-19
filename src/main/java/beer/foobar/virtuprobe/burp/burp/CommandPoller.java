package beer.foobar.virtuprobe.burp.burp;

import beer.foobar.virtuprobe.burp.client.VirtuProbeClient;
import beer.foobar.virtuprobe.burp.config.BridgeConfig;
import beer.foobar.virtuprobe.burp.config.ConfigStore;
import beer.foobar.virtuprobe.burp.model.BridgeCommand;
import burp.api.montoya.logging.Logging;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The VirtuProbe to Burp direction: long polls the command queue and hands each command to
 * {@link CommandApplier}.
 *
 * <p>Without this, Send to Burp is a button that does nothing. The VirtuProbe side was complete and
 * queued the command; nothing ever drained it, so it expired after five minutes and no error appeared
 * at either end. That is why {@link #state()} exists and is shown in the VirtuProbe tab: a poller
 * that has quietly stopped is indistinguishable from one that is running and finding nothing, and this
 * feature's whole failure mode is silence.
 *
 * <p><b>The queue drains destructively on poll and there is no ack.</b> So a command this extension
 * pulls and then fails to apply is gone, and the user has to click Send to Burp again. The design had
 * an ack endpoint and the server shipped without one; recorded here so the next reader knows it is a
 * known shape rather than an oversight.
 */
public class CommandPoller {

    /** How long the server is asked to hold a poll open. Its own cap is 30 seconds. */
    private static final int WAIT_SECONDS = 25;

    /** Backoff after a failed poll, so an unreachable VirtuProbe is not hammered. */
    private static final long RETRY_DELAY_MS = 10_000;

    /** What the settings panel reports, so a silent poller can be told apart from a stopped one. */
    public enum State {
        STOPPED("Not polling VirtuProbe for Send to Burp."),
        POLLING("Listening for Send to Burp."),
        UNREACHABLE("Cannot reach VirtuProbe. Retrying.");

        private final String message;

        State(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final VirtuProbeClient client;
    private final ConfigStore configStore;
    private final CommandApplier applier;
    private final Logging logging;
    private final Consumer<State> onStateChange;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile State state = State.STOPPED;
    private volatile Thread thread;

    public CommandPoller(VirtuProbeClient client, ConfigStore configStore, CommandApplier applier,
                         Logging logging, Consumer<State> onStateChange) {
        this.client = client;
        this.configStore = configStore;
        this.applier = applier;
        this.logging = logging;
        this.onStateChange = onStateChange;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Starts polling. Calling this while already running does nothing. */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // A daemon thread so a poller that is mid-wait cannot hold Burp open on exit.
        final Thread worker = new Thread(this::loop, "vp-burp-command-poller");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    /**
     * Stops polling. Interrupts the in-flight request rather than waiting for it, because a poll can
     * legitimately be parked for 25 seconds and an unload must not block that long.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        final Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
        }
        thread = null;
        setState(State.STOPPED);
    }

    private void loop() {
        setState(State.POLLING);
        while (running.get()) {
            final BridgeConfig config = configStore.load();
            try {
                final List<BridgeCommand> commands = client.pollCommands(config, WAIT_SECONDS);
                if (!running.get()) {
                    return;
                }
                setState(State.POLLING);
                for (BridgeCommand command : commands) {
                    applier.apply(command);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                // One log line per transition, not per failed poll: VirtuProbe being closed is the
                // normal case, and a line every ten seconds would bury everything else in the log.
                if (state != State.UNREACHABLE) {
                    logging.logToOutput("VirtuProbe is not answering the Burp bridge. Retrying every "
                            + (RETRY_DELAY_MS / 1000) + "s. (" + e.getMessage() + ")");
                }
                setState(State.UNREACHABLE);
                if (!sleepBeforeRetry()) {
                    return;
                }
            }
        }
    }

    /** @return false when interrupted, so the caller stops instead of looping. */
    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void setState(State next) {
        if (state == next) {
            return;
        }
        state = next;
        if (onStateChange != null) {
            onStateChange.accept(next);
        }
    }
}
