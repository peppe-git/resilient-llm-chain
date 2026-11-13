// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A scripted provider for tests. Each call consumes the next scripted behaviour (or the default one) and
 * is published on {@link #awaitCall()} before the behaviour runs, so a test can wait for "the provider
 * has been entered" without sleeping.
 */
final class FakeProvider implements LlmProvider<Prompt> {

    /** What one call does. */
    @FunctionalInterface
    interface Behaviour {
        LlmResult run(Call call) throws Exception;
    }

    /** One call received by the provider. A blocked call waits until the test completes {@link #reply}. */
    static final class Call {
        final String model;
        final StageContext context;
        final CompletableFuture<LlmResult> reply = new CompletableFuture<>();
        volatile Thread thread;

        Call(String model, StageContext context) {
            this.model = model;
            this.context = context;
        }

        void answer(String text) {
            reply.complete(LlmResult.of(text, model, 100, 50));
        }

        void fail(Exception error) {
            reply.completeExceptionally(error);
        }
    }

    private final String name;
    private final String defaultModel;
    private final List<String> models;
    private final ConcurrentLinkedQueue<Behaviour> script = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<Call> entered = new LinkedBlockingQueue<>();
    private final List<Call> history = Collections.synchronizedList(new ArrayList<>());
    private volatile Behaviour otherwise;
    volatile boolean available = true;

    FakeProvider(String name) {
        this(name, name + "-model", List.of());
    }

    FakeProvider(String name, String defaultModel, List<String> models) {
        this.name = name;
        this.defaultModel = defaultModel;
        this.models = models;
        this.otherwise = answering(name + " answer");
    }

    static Behaviour answering(String text) {
        return call -> LlmResult.of(text, call.model, 100, 50);
    }

    static Behaviour failing(Exception error) {
        return call -> {
            throw error;
        };
    }

    static Behaviour blocking() {
        return call -> {
            try {
                return call.reply.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception exception) {
                    throw exception;
                }
                throw e;
            }
        };
    }

    /** Scripts the next calls, in order. */
    FakeProvider then(Behaviour... behaviours) {
        Collections.addAll(script, behaviours);
        return this;
    }

    FakeProvider thenAnswer(String text) {
        return then(answering(text));
    }

    FakeProvider thenFail(Exception error) {
        return then(failing(error));
    }

    FakeProvider thenBlock() {
        return then(blocking());
    }

    /** Behaviour of every call beyond the script. */
    FakeProvider otherwise(Behaviour behaviour) {
        this.otherwise = behaviour;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public List<String> models() {
        return models;
    }

    @Override
    public LlmResult generate(Prompt request, String model, StageContext context) throws Exception {
        Call call = new Call(model, context);
        call.thread = Thread.currentThread();
        history.add(call);
        Behaviour behaviour = script.poll();
        entered.add(call);
        return (behaviour != null ? behaviour : otherwise).run(call);
    }

    /** Waits for the next call to enter the provider. */
    Call awaitCall() throws InterruptedException {
        Call call = entered.poll(5, TimeUnit.SECONDS);
        if (call == null) {
            fail("provider '" + name + "' was not called");
        }
        return call;
    }

    int calls() {
        return history.size();
    }

    List<String> calledModels() {
        synchronized (history) {
            return history.stream().map(c -> c.model).toList();
        }
    }
}
