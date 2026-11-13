// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.error.ProviderHttpException;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ChainStreamTest {

    private static final Prompt PROMPT = Prompt.of("hello");

    private final List<String> preview = new CopyOnWriteArrayList<>();
    private final StreamListener listener = new StreamListener() {
        @Override
        public void delta(String text) {
            preview.add(text);
        }

        @Override
        public void reset() {
            preview.add("<reset>");
        }
    };

    private ResilientChain<Prompt> chain(RetryPolicy retry, FakeProvider... providers) {
        ResilientChain.Builder<Prompt> builder = ResilientChain.<Prompt>builder()
                .clock(new ManualChainClock())
                .retry(retry);
        for (FakeProvider provider : providers) {
            builder.provider(provider);
        }
        return builder.build();
    }

    @Test
    void theWinnersPiecesReachTheListener() {
        FakeProvider provider = new FakeProvider("p").then(call -> {
            call.context.emit("Hel");
            call.context.emit("lo");
            return LlmResult.of("Hello");
        });

        ChainResult result = chain(RetryPolicy.none(), provider)
                .execute(PROMPT, CallOptions.interactive().withStream(listener));

        assertThat(result.text()).isEqualTo("Hello");
        assertThat(preview).containsExactly("Hel", "lo");
    }

    @Test
    void aRetry_resetsThePreview() {
        FakeProvider provider = new FakeProvider("p").then(call -> {
            call.context.emit("partial");
            throw new ProviderHttpException("p", 503, "0", null);
        }, call -> {
            call.context.emit("full");
            return LlmResult.of("full");
        });

        chain(RetryPolicy.defaults(), provider).execute(PROMPT, CallOptions.interactive().withStream(listener));

        assertThat(preview).containsExactly("partial", "<reset>", "full");
    }

    @Test
    void aFailedOwner_resetsThePreview_andTheNextStageTakesTheSeat() {
        FakeProvider first = new FakeProvider("first").then(call -> {
            call.context.emit("first draft");
            throw new ProviderHttpException("first", 401, null, null);
        });
        FakeProvider second = new FakeProvider("second").then(call -> {
            call.context.emit("second");
            return LlmResult.of("second");
        });

        chain(RetryPolicy.none(), first, second).execute(PROMPT, CallOptions.interactive().withStream(listener));

        assertThat(preview).containsExactly("first draft", "<reset>", "second");
    }

    @Test
    void emit_isANoOp_withoutAListener() {
        FakeProvider provider = new FakeProvider("p").then(call -> {
            call.context.emit("nobody listens");
            return LlmResult.of("done");
        });

        assertThat(chain(RetryPolicy.none(), provider).execute(PROMPT).text()).isEqualTo("done");
    }
}
