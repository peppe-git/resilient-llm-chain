// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class ChainCancellationTest {

    private static final Prompt PROMPT = Prompt.of("hello");

    private final Recorder recorder = new Recorder();

    private ResilientChain<Prompt> chain(FakeProvider... providers) {
        ResilientChain.Builder<Prompt> builder = ResilientChain.<Prompt>builder()
                .clock(new ManualChainClock())
                .retry(RetryPolicy.none())
                .usageListener(recorder);
        for (FakeProvider provider : providers) {
            builder.provider(provider);
        }
        return builder.build();
    }

    @Test
    void cancelledBeforeTheStart_callsNothing() {
        FakeProvider provider = new FakeProvider("p");
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel();

        assertThatThrownBy(() -> chain(provider).execute(PROMPT, CallOptions.background().withCancellation(cancellation)))
                .isInstanceOf(ChainCancelledException.class);
        assertThat(provider.calls()).isZero();
    }

    @Test
    void cancellingWhileAStageIsInFlight_returnsAtOnce_andTheStageStillReportsItsSpend() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup);
        CancellationSource cancellation = new CancellationSource();

        CompletableFuture<ChainResult> call = Async.run(() ->
                chain.execute(PROMPT, CallOptions.background().withCancellation(cancellation)));
        FakeProvider.Call stuck = primary.awaitCall();
        cancellation.cancel();

        assertThatThrownBy(() -> Async.join(call))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(ChainCancelledException.class);
        assertThat(backup.calls()).isZero();

        stuck.answer("nobody is listening");
        assertThat(recorder.awaitUsage().outcome()).isEqualTo(Outcome.DISCARDED);
    }

    @Test
    void anInterruptedCaller_getsACancellation_andKeepsItsInterruptFlag() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        ResilientChain<Prompt> chain = chain(primary);
        CompletableFuture<Boolean> interruptFlag = new CompletableFuture<>();
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();

        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                chain.execute(PROMPT);
            } catch (Throwable t) {
                thrown.complete(t);
            }
            interruptFlag.complete(Thread.currentThread().isInterrupted());
        });
        FakeProvider.Call stuck = primary.awaitCall();
        caller.interrupt();

        assertThat(Async.join(thrown)).isInstanceOf(ChainCancelledException.class);
        assertThat(Async.join(interruptFlag)).isTrue();
        stuck.answer("late");
    }
}
