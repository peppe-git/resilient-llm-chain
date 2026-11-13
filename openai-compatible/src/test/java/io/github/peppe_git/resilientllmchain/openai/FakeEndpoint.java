// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** A loopback HTTP server answering with scripted responses and recording the requests. */
final class FakeEndpoint implements AutoCloseable {

    record Reply(int status, Map<String, String> headers, String body) {
    }

    record Received(String path, String authorization, String body) {
    }

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    final List<Received> received = new CopyOnWriteArrayList<>();

    FakeEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    FakeEndpoint reply(int status, Map<String, String> headers, String body) {
        replies.add(new Reply(status, headers, body));
        return this;
    }

    FakeEndpoint json(String body) {
        return reply(200, Map.of("Content-Type", "application/json"), body);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));
        Reply reply = replies.poll();
        if (reply == null) {
            reply = new Reply(500, Map.of(), "no scripted reply");
        }
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
