// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-call options. Immutable; the {@code with...} methods return modified copies.
 *
 * <ul>
 *   <li>{@link #interactive()}: someone is waiting for the answer. Selects the interactive hedge delay
 *       and the bounded wait of the concurrency limiter.</li>
 *   <li>{@link #cancellation()}: cooperative cancellation.</li>
 *   <li>{@link #stream()}: receives a streamed preview, or {@code null}.</li>
 *   <li>{@link #labels()}: free-form attribution (tenant, job, purpose) handed to listeners.</li>
 *   <li>{@link #validator()}: overrides the chain's output validator for this call, or {@code null}.</li>
 * </ul>
 */
public final class CallOptions {

    private static final CallOptions BACKGROUND =
            new CallOptions(false, CancellationToken.NONE, null, Map.of(), null);

    private final boolean interactive;
    private final CancellationToken cancellation;
    private final StreamListener stream;
    private final Map<String, String> labels;
    private final OutputValidator validator;

    private CallOptions(boolean interactive, CancellationToken cancellation, StreamListener stream,
            Map<String, String> labels, OutputValidator validator) {
        this.interactive = interactive;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.stream = stream;
        this.labels = Map.copyOf(labels);
        this.validator = validator;
    }

    /** Background work: nobody is waiting on the answer. */
    public static CallOptions background() {
        return BACKGROUND;
    }

    /** Someone is waiting on the answer. */
    public static CallOptions interactive() {
        return BACKGROUND.withInteractive(true);
    }

    public CallOptions withInteractive(boolean value) {
        return new CallOptions(value, cancellation, stream, labels, validator);
    }

    public CallOptions withCancellation(CancellationToken token) {
        return new CallOptions(interactive, token, stream, labels, validator);
    }

    public CallOptions withStream(StreamListener listener) {
        return new CallOptions(interactive, cancellation, listener, labels, validator);
    }

    public CallOptions withLabel(String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(labels);
        copy.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return new CallOptions(interactive, cancellation, stream, copy, validator);
    }

    public CallOptions withLabels(Map<String, String> values) {
        return new CallOptions(interactive, cancellation, stream, values, validator);
    }

    public CallOptions withValidator(OutputValidator value) {
        return new CallOptions(interactive, cancellation, stream, labels, value);
    }

    public boolean isInteractive() {
        return interactive;
    }

    public CancellationToken cancellation() {
        return cancellation;
    }

    public StreamListener stream() {
        return stream;
    }

    public Map<String, String> labels() {
        return labels;
    }

    public OutputValidator validator() {
        return validator;
    }
}
