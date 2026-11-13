// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.CancellationToken;
import io.github.peppe_git.resilientllmchain.ChainResult;
import io.github.peppe_git.resilientllmchain.LlmResult;
import io.github.peppe_git.resilientllmchain.Prompt;
import io.github.peppe_git.resilientllmchain.ResilientChain;
import io.github.peppe_git.resilientllmchain.StageContext;
import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.Usage;
import io.github.peppe_git.resilientllmchain.error.ContentRefusedException;
import io.github.peppe_git.resilientllmchain.error.ErrorClassifier;
import io.github.peppe_git.resilientllmchain.error.ErrorKind;
import io.github.peppe_git.resilientllmchain.error.ProviderHttpException;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderTest {

    private static final Prompt PROMPT = new Prompt("Be brief.", "Say hi.");
    private static final String OK_BODY = """
            {"model": "example-small-0001",
             "choices": [{"message": {"role": "assistant", "content": "Hi!"}, "finish_reason": "stop"}],
             "usage": {"prompt_tokens": 120, "completion_tokens": 4,
                       "prompt_tokens_details": {"cached_tokens": 100}}}
            """;

    private final FakeEndpoint endpoint;
    private final List<String> emitted = new CopyOnWriteArrayList<>();

    OpenAiCompatibleProviderTest() throws IOException {
        endpoint = new FakeEndpoint();
    }

    @AfterEach
    void stop() {
        endpoint.close();
    }

    private OpenAiCompatibleProvider.Builder provider() {
        return OpenAiCompatibleProvider.builder("example", endpoint.baseUri())
                .apiKey(() -> "test-key")
                .defaultModel("example-small");
    }

    private final StageContext context = new StageContext() {
        @Override
        public StageRef stage() {
            return new StageRef("example", "example-small");
        }

        @Override
        public int attempt() {
            return 1;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public CancellationToken cancellation() {
            return CancellationToken.NONE;
        }

        @Override
        public Map<String, String> labels() {
            return Map.of();
        }

        @Override
        public void emit(String text) {
            emitted.add(text);
        }
    };

    @Test
    void sendsTheChatRequest_andReadsTextModelAndUsage() throws Exception {
        endpoint.json(OK_BODY);

        LlmResult result = provider().temperature(0.2).maxTokens(256).build().generate(PROMPT, null, context);

        assertThat(result.text()).isEqualTo("Hi!");
        assertThat(result.model()).isEqualTo("example-small-0001");
        assertThat(result.usage()).isEqualTo(new Usage(20, 4, 100, 0));
        FakeEndpoint.Received request = endpoint.received.getFirst();
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer test-key");
        assertThat(request.body())
                .contains("\"model\":\"example-small\"")
                .contains("{\"role\":\"system\",\"content\":\"Be brief.\"}")
                .contains("{\"role\":\"user\",\"content\":\"Say hi.\"}")
                .contains("\"temperature\":0.2")
                .contains("\"max_tokens\":256");
    }

    @Test
    void anErrorStatus_becomesAProviderHttpException_withRetryAfterAndBody() {
        endpoint.reply(429, Map.of("Retry-After", "7"), "{\"error\": \"slow down\"}");

        assertThatThrownBy(() -> provider().build().generate(PROMPT, "example-large", context))
                .isInstanceOfSatisfying(ProviderHttpException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(429);
                    assertThat(e.retryAfterHeader()).isEqualTo("7");
                    assertThat(e.bodyExcerpt()).contains("slow down");
                    assertThat(ErrorClassifier.defaults().classify(e).retryAfter()).isEqualTo(Duration.ofSeconds(7));
                });
        assertThat(endpoint.received.getFirst().body()).contains("\"model\":\"example-large\"");
    }

    @Test
    void aRetiredModel_isClassifiedAsPermanent() {
        endpoint.reply(410, Map.of(), "{\"detail\": \"model retired\"}");

        assertThatThrownBy(() -> provider().build().generate(PROMPT, null, context))
                .satisfies(e -> assertThat(ErrorClassifier.defaults().classify(e).kind()).isEqualTo(ErrorKind.PERMANENT));
    }

    @Test
    void jsonLabelledAsBinary_isStillRead() throws Exception {
        endpoint.reply(200, Map.of("Content-Type", "application/octet-stream"), OK_BODY);

        assertThat(provider().build().generate(PROMPT, null, context).text()).isEqualTo("Hi!");
    }

    @Test
    void anEmptyBody_saysWhatArrived() {
        endpoint.reply(200, Map.of("Content-Type", "application/json"), "");

        assertThatThrownBy(() -> provider().build().generate(PROMPT, null, context))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 200").hasMessageContaining("0 bytes").hasMessageContaining("empty body");
    }

    @Test
    void aBodyThatIsNotJson_isReportedWithAnExcerpt() {
        endpoint.reply(200, Map.of("Content-Type", "text/html"), "<html>gateway error</html>");

        assertThatThrownBy(() -> provider().build().generate(PROMPT, null, context))
                .isInstanceOf(IOException.class).hasMessageContaining("<html>gateway error</html>");
    }

    @Test
    void theContentFilter_isAContentRefusal() {
        endpoint.json("{\"choices\": [{\"message\": {\"content\": \"\"}, \"finish_reason\": \"content_filter\"}]}");

        assertThatThrownBy(() -> provider().build().generate(PROMPT, null, context))
                .isInstanceOf(ContentRefusedException.class);
    }

    @Test
    void streaming_forwardsEachPiece_andAssemblesTheAnswer() throws Exception {
        endpoint.reply(200, Map.of("Content-Type", "text/event-stream"), """
                data: {"model":"example-small-0001","choices":[{"delta":{"content":"Hel"}}]}

                data: {"choices":[{"delta":{"content":"lo"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2}}

                data: [DONE]

                """);

        LlmResult result = provider().streaming(true).build().generate(PROMPT, null, context);

        assertThat(emitted).containsExactly("Hel", "lo");
        assertThat(result.text()).isEqualTo("Hello");
        assertThat(result.model()).isEqualTo("example-small-0001");
        assertThat(result.usage()).isEqualTo(Usage.of(12, 2));
        assertThat(endpoint.received.getFirst().body()).contains("\"stream\":true").contains("include_usage");
    }

    @Test
    void aStreamThatStopsMidway_isAFailure() {
        endpoint.reply(200, Map.of("Content-Type", "text/event-stream"), """
                data: {"choices":[{"delta":{"content":"Hel"}}]}

                """);

        assertThatThrownBy(() -> provider().streaming(true).build().generate(PROMPT, null, context))
                .isInstanceOf(IOException.class).hasMessageContaining("stream ended before completion");
    }

    @Test
    void withoutAKey_theProviderIsUnavailable() {
        assertThat(provider().apiKey(() -> " ").build().available()).isFalse();
        assertThat(provider().build().available()).isTrue();
    }

    @Test
    void inAChain_aServerThatKeepsFailing_isRetriedThenReplaced() throws Exception {
        try (FakeEndpoint backupEndpoint = new FakeEndpoint()) {
            endpoint.reply(503, Map.of("Retry-After", "1"), "busy")
                    .reply(503, Map.of("Retry-After", "1"), "busy")
                    .reply(503, Map.of(), "busy");
            backupEndpoint.json(OK_BODY);
            ManualChainClock clock = new ManualChainClock();
            ResilientChain<Prompt> chain = ResilientChain.<Prompt>builder()
                    .clock(clock)
                    .retry(RetryPolicy.builder().maxAttempts(3).random(() -> 0.5).build())
                    .provider(provider().build())
                    .provider(OpenAiCompatibleProvider.builder("backup", backupEndpoint.baseUri())
                            .apiKey(() -> "other-key").defaultModel("backup-model").build())
                    .build();
            long start = clock.millis();

            ChainResult result = chain.execute(PROMPT);

            assertThat(result.stage().provider()).isEqualTo("backup");
            assertThat(endpoint.received).hasSize(3);
            assertThat(clock.millis() - start).as("two Retry-After waits of one second").isEqualTo(2_000);
        }
    }
}
