package beer.foobar.virtuprobe.burp.burp;

import beer.foobar.virtuprobe.burp.client.VirtuProbeClient;
import beer.foobar.virtuprobe.burp.config.BridgeConfig;
import beer.foobar.virtuprobe.burp.config.ConfigStore;
import beer.foobar.virtuprobe.burp.model.BridgeCommand;
import burp.api.montoya.logging.Logging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The loop that makes Send to Burp arrive at all.
 *
 * <p>Its whole failure mode is silence, so these cases are about the loop SURVIVING: a poll that
 * throws, a command that cannot be applied, and a stop that has to return promptly while a poll is
 * parked. Assertions wait on a latch with a generous deadline rather than sleeping a guessed interval,
 * because a timing assertion tuned to this machine turns into a flaky test on a shared runner, which
 * is worse than a failing one: the next real regression gets waved through as "the runner again".
 */
class CommandPollerTest {

    /** Long enough that only a genuinely stuck poller fails, short enough to not stall a build. */
    private static final long DEADLINE_SECONDS = 5;

    private VirtuProbeClient client;
    private ConfigStore configStore;
    private CommandApplier applier;
    private Logging logging;
    private CommandPoller poller;

    @BeforeEach
    void setUp() {
        client = mock(VirtuProbeClient.class);
        configStore = mock(ConfigStore.class);
        applier = mock(CommandApplier.class);
        logging = mock(Logging.class);
        when(configStore.load()).thenReturn(BridgeConfig.defaults());
    }

    @AfterEach
    void stopPoller() {
        if (poller != null) {
            poller.stop();
        }
    }

    private CommandPoller poller(java.util.function.Consumer<CommandPoller.State> onState) {
        // 0s wait and a 10ms backoff: the loop's shape is under test, not the intervals it ships with.
        return new CommandPoller(client, configStore, applier, logging, onState, 0, 10);
    }

    private static BridgeCommand command(String id) {
        return new BridgeCommand(id, "SEND_TO_REPEATER", "cmVx", "example.test", 443, true, "Login", 0L);
    }

    @Test
    void everyCommandItReceivesIsApplied() throws Exception {
        CountDownLatch applied = new CountDownLatch(2);
        List<String> seen = new CopyOnWriteArrayList<>();
        when(applier.apply(any())).thenAnswer(call -> {
            seen.add(((BridgeCommand) call.getArgument(0)).id());
            applied.countDown();
            return true;
        });
        when(client.pollCommands(any(), anyInt()))
                .thenReturn(List.of(command("c-1"), command("c-2")))
                .thenReturn(List.of());

        poller = poller(state -> { });
        poller.start();

        assertTrue(applied.await(DEADLINE_SECONDS, TimeUnit.SECONDS), "both commands were applied");
        assertEquals(List.of("c-1", "c-2"), seen);
    }

    /**
     * A poll that throws is the normal case, not an exceptional one: VirtuProbe is closed most of the
     * time. The loop has to come back, or the user restarts Burp to get Send to Burp working again.
     */
    @Test
    void aFailedPollDoesNotEndTheLoop() throws Exception {
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger polls = new AtomicInteger();
        when(client.pollCommands(any(), anyInt())).thenAnswer(call -> {
            if (polls.incrementAndGet() == 1) {
                throw new IllegalStateException("connection refused");
            }
            recovered.countDown();
            return List.of();
        });

        poller = poller(state -> { });
        poller.start();

        assertTrue(recovered.await(DEADLINE_SECONDS, TimeUnit.SECONDS),
                "the loop polled again after a failure");
    }

    /** Listening, stopped and unreachable look identical from outside, so the panel is told which. */
    @Test
    void stateGoesUnreachableOnFailureAndBackToPollingOnRecovery() throws Exception {
        CountDownLatch sawUnreachable = new CountDownLatch(1);
        CountDownLatch sawPollingAgain = new CountDownLatch(1);
        AtomicInteger polls = new AtomicInteger();
        when(client.pollCommands(any(), anyInt())).thenAnswer(call -> {
            if (polls.incrementAndGet() == 1) {
                throw new IllegalStateException("connection refused");
            }
            return List.of();
        });

        poller = poller(state -> {
            if (state == CommandPoller.State.UNREACHABLE) {
                sawUnreachable.countDown();
            } else if (state == CommandPoller.State.POLLING && sawUnreachable.getCount() == 0) {
                sawPollingAgain.countDown();
            }
        });
        poller.start();

        assertTrue(sawUnreachable.await(DEADLINE_SECONDS, TimeUnit.SECONDS), "reported unreachable");
        assertTrue(sawPollingAgain.await(DEADLINE_SECONDS, TimeUnit.SECONDS), "reported listening again");
    }

    /** A command that cannot be applied costs that command, never the loop and everything after it. */
    @Test
    void aCommandThatCannotBeAppliedDoesNotStopTheNextOne() throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        when(applier.apply(any())).thenAnswer(call -> {
            both.countDown();
            return false;
        });
        when(client.pollCommands(any(), anyInt()))
                .thenReturn(List.of(command("c-1"), command("c-2")))
                .thenReturn(List.of());

        poller = poller(state -> { });
        poller.start();

        assertTrue(both.await(DEADLINE_SECONDS, TimeUnit.SECONDS), "the second command was still tried");
    }

    @Test
    void stopEndsTheLoopAndReportsStopped() throws Exception {
        CountDownLatch polling = new CountDownLatch(1);
        when(client.pollCommands(any(), anyInt())).thenAnswer(call -> {
            polling.countDown();
            Thread.sleep(50);
            return List.of();
        });
        CountDownLatch stopped = new CountDownLatch(1);

        poller = poller(state -> {
            if (state == CommandPoller.State.STOPPED) {
                stopped.countDown();
            }
        });
        poller.start();
        assertTrue(polling.await(DEADLINE_SECONDS, TimeUnit.SECONDS), "it started polling");

        poller.stop();

        assertTrue(stopped.await(DEADLINE_SECONDS, TimeUnit.SECONDS), "it reported stopped");
        assertFalse(poller.isRunning());
    }

    @Test
    void startingTwiceRunsOneLoopNotTwo() throws Exception {
        CountDownLatch polled = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        when(client.pollCommands(any(), anyInt())).thenAnswer(call -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            Thread.sleep(30);
            concurrent.decrementAndGet();
            polled.countDown();
            return List.of();
        });

        poller = poller(state -> { });
        poller.start();
        poller.start();

        assertTrue(polled.await(DEADLINE_SECONDS, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertEquals(1, maxConcurrent.get(), "a second start must not add a second polling thread");
    }
}
