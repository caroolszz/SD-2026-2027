import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Nó IPFS simulado (só para testes, sem o executável Kubo): implementa pubsub/pub e pubsub/sub
 * com a mesma codificação multibase e o mesmo formato de linhas JSON. Vários nós podem ser ligados
 * entre si (peers) para simular o cenário de dois nós. Uso: java MockIpfs <porto> [portoPeer...]
 */
public class MockIpfs {
    static final Map<String, List<BlockingQueue<String>>> subs = new ConcurrentHashMap<>();
    static final HttpClient http = HttpClient.newHttpClient();
    static final List<Integer> peers = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        for (int i = 1; i < args.length; i++) peers.add(Integer.parseInt(args[i]));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v0/pubsub/pub", ex -> {
            String topic = arg(ex);
            byte[] body = ex.getRequestBody().readAllBytes();
            String s = new String(body, StandardCharsets.UTF_8);
            int a = s.indexOf("\r\n\r\n") + 4;
            int b = s.lastIndexOf("\r\n--");
            String payload = s.substring(a, b);
            deliver(topic, payload);
            for (int p : peers) {
                HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + "/internal/deliver?arg=" + topic))
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
                http.sendAsync(r, HttpResponse.BodyHandlers.discarding());
            }
            reply(ex, 200, "");
        });
        server.createContext("/internal/deliver", ex -> {
            deliver(arg(ex), new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "");
        });
        server.createContext("/api/v0/pubsub/sub", ex -> {
            String topic = arg(ex);
            BlockingQueue<String> q = new LinkedBlockingQueue<>();
            subs.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(q);
            ex.sendResponseHeaders(200, 0);                         // chunked
            try (OutputStream os = ex.getResponseBody()) {
                os.write("{}\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                while (true) {
                    String m = q.take();
                    os.write(m.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException | InterruptedException e) {
                // subscritor desligou
            } finally {
                subs.get(topic).remove(q);
            }
        });
        server.start();
        System.out.println("MockIpfs em :" + port + " peers=" + peers);
    }

    static String arg(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return q.substring(q.indexOf("arg=") + 4);
    }

    static void deliver(String topic, String payload) {
        String data = "u" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String json = "{\"from\":\"mock\",\"data\":\"" + data + "\",\"seqno\":\"x\",\"topicIDs\":[\"" + topic + "\"]}\n";
        for (BlockingQueue<String> q : subs.getOrDefault(topic, List.of())) q.add(json);
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }
}
