// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.fail;

import io.github.peppe_git.resilientllmchain.event.ChainEventListener;
import io.github.peppe_git.resilientllmchain.event.HedgeSuppression;
import io.github.peppe_git.resilientllmchain.event.SkipReason;
import io.github.peppe_git.resilientllmchain.event.UsageEvent;
import io.github.peppe_git.resilientllmchain.event.UsageListener;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Records usage events and chain decisions. */
final class Recorder implements UsageListener, ChainEventListener {

    record Skip(StageRef stage, SkipReason reason) {
    }

    record Suppressed(StageRef candidate, HedgeSuppression reason) {
    }

    final List<UsageEvent> usage = new CopyOnWriteArrayList<>();
    final BlockingQueue<UsageEvent> usageQueue = new LinkedBlockingQueue<>();
    final List<Skip> skips = new CopyOnWriteArrayList<>();
    final List<Suppressed> suppressed = new CopyOnWriteArrayList<>();
    final BlockingQueue<Suppressed> suppressedQueue = new LinkedBlockingQueue<>();
    final List<StageRef> started = new CopyOnWriteArrayList<>();
    final List<StageRef> hedges = new CopyOnWriteArrayList<>();
    final List<StageRef> permanent = new CopyOnWriteArrayList<>();
    volatile int failOpens;
    volatile int stoppedByRejections;

    @Override
    public void onUsage(UsageEvent event) {
        usage.add(event);
        usageQueue.add(event);
    }

    /** Waits for the next usage event. */
    UsageEvent awaitUsage() throws InterruptedException {
        UsageEvent event = usageQueue.poll(5, TimeUnit.SECONDS);
        if (event == null) {
            fail("no usage event");
        }
        return event;
    }

    /** Waits until a usage event with {@code outcome} arrives, skipping others. */
    UsageEvent awaitUsage(Outcome outcome) throws InterruptedException {
        while (true) {
            UsageEvent event = awaitUsage();
            if (event.outcome() == outcome) {
                return event;
            }
        }
    }

    @Override
    public void onStageSkipped(StageRef stage, SkipReason reason) {
        skips.add(new Skip(stage, reason));
    }

    @Override
    public void onStageStarted(StageRef stage, boolean hedge) {
        started.add(stage);
        if (hedge) {
            hedges.add(stage);
        }
    }

    @Override
    public void onHedgeSuppressed(StageRef candidate, HedgeSuppression reason) {
        Suppressed event = new Suppressed(candidate, reason);
        suppressed.add(event);
        suppressedQueue.add(event);
    }

    /** Waits for the next suppressed hedge. */
    Suppressed awaitSuppressed() throws InterruptedException {
        Suppressed event = suppressedQueue.poll(5, TimeUnit.SECONDS);
        if (event == null) {
            fail("no hedge was suppressed");
        }
        return event;
    }

    @Override
    public void onFailOpen() {
        failOpens++;
    }

    @Override
    public void onStoppedByRejections(int rejections, int remainingStages) {
        stoppedByRejections++;
    }

    @Override
    public void onPermanentFailure(StageRef stage, Throwable failure) {
        permanent.add(stage);
    }
}
