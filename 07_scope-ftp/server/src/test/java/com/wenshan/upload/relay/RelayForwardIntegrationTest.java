package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wenshan.upload.audit.AuditStore;
import com.wenshan.upload.audit.RelayViaHop;
import com.wenshan.upload.dto.AuditLogItem;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.RelayTestClient;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelayForwardFilter 真实端口集成测试：JDK HttpServer 假 B（以及假 C）验证
 * 链头弹出、路径/query/body 保留、每跳签名头、X-Relay-Via 追加、错误映射与 SSRF 拒绝。
 *
 * <p>MockMvc 会提前消费 multipart/流式 body，因此 relay 用例必须走真实端口。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.relay.enabled=true",
                "app.relay.allow-loopback=true",
                "app.relay.request-timeout-ms=1000"
        })
@ActiveProfiles("test")
class RelayForwardIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OK_JSON = "{\"code\":0,\"message\":\"ok\",\"data\":{\"from\":\"b\"}}";

    @LocalServerPort
    int port;

    @Autowired
    AuditStore auditStore;

    private HttpServer downstream;
    private String downstreamUrl;
    private RelayTestClient client;
    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>(new byte[0]);

    @BeforeEach
    void startDownstream() throws IOException {
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/api/files", exchange -> {
            capture(exchange);
            respond(exchange, 200, "application/json", OK_JSON.getBytes(StandardCharsets.UTF_8),
                    "b-request-1");
        });
        downstream.createContext("/api/downstream/401", exchange ->
                respondJson(exchange, 401, 40100, "签名无效"));
        downstream.createContext("/api/downstream/403-identity", exchange ->
                respondJson(exchange, 403, 40312, "缺少设备标识"));
        downstream.createContext("/api/downstream/403-device", exchange ->
                respondJson(exchange, 403, 40300, "设备未授权"));
        downstream.createContext("/api/downstream/429", exchange ->
                respondJson(exchange, 429, 42900, "请求过于频繁"));
        downstream.createContext("/api/downstream/500", exchange ->
                respondJson(exchange, 500, 50000, "服务器内部错误"));
        downstream.createContext("/api/downstream/business-404", exchange ->
                respondJson(exchange, 404, 40400, "资源不存在"));
        downstream.createContext("/api/downstream/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        downstream.createContext("/api/downstream/bad-response", exchange ->
                respond(exchange, 502, "text/plain", "bad gateway".getBytes(StandardCharsets.UTF_8), null));
        downstream.createContext("/api/slow", exchange -> {
            try {
                Thread.sleep(3_000);
                respond(exchange, 200, "application/json", OK_JSON.getBytes(StandardCharsets.UTF_8), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 调用方已超时断开；忽略写回失败
            }
        });
        downstream.start();
        downstreamUrl = "http://127.0.0.1:" + downstream.getAddress().getPort();
        client = new RelayTestClient(port);
        seen.clear();
        seenBody.set(new byte[0]);
    }

    @AfterEach
    void stopDownstream() {
        downstream.stop(0);
    }

    @Test
    void forwardsJsonGetAndAppendsVia() throws Exception {
        HttpResponse<byte[]> response = forward("GET", "/api/files", "keyword=abc", null, null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(RelayTestClient.utf8(response.body())).contains("\"from\":\"b\"");
        assertThat(seen.get("method")).isEqualTo("GET");
        assertThat(seen.get("uri")).isEqualTo("/api/files?keyword=abc");
        assertThat(seen.get("appKey")).isEqualTo(RelayTestClient.B_APP_KEY);
        assertThat(seen.get("deviceId")).isEqualTo(RelayTestClient.B_DEVICE_ID);
        // 最终目标不再带链数组
        assertThat(seen).doesNotContainKey("chain");
        // 每跳签名可用 B 的假 secret 复算
        assertThat(RelayTestClient.signatureMatches(
                RelayTestClient.B_APP_SECRET,
                RelayTestClient.B_APP_KEY,
                seen.get("timestamp"),
                seen.get("nonce"),
                "GET",
                "/api/files",
                "keyword=abc",
                SignatureService.emptyPayloadHash(),
                seen.get("signature"))).isTrue();
        // 来源链追加当前跳 A
        List<RelayViaHop> via = RelayTestClient.decodeVia(seen.get("via"));
        assertThat(via).extracting(RelayViaHop::name).containsExactly("A");
        assertThat(via.get(0).appKey()).isEqualTo(TestSigner.APP_KEY);
        assertThat(via.get(0).url()).isEqualTo("http://127.0.0.1:" + port);
        // 下游 X-Request-Id 以 X-Relay-Request-Id 回传；A 自己的 X-Request-Id 不被覆盖
        assertThat(response.headers().firstValue("X-Relay-Request-Id")).contains("b-request-1");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent()
                .get().isNotEqualTo("b-request-1");
    }

    @Test
    void forwardsJsonPostBodyAndPerHopSignature() throws Exception {
        byte[] body = "{\"path\":\"docs/a.txt\",\"newName\":\"b.txt\"}".getBytes(StandardCharsets.UTF_8);

        HttpResponse<byte[]> response = forward("POST", "/api/files", null, body, "application/json", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(seen.get("method")).isEqualTo("POST");
        assertThat(seenBody.get()).isEqualTo(body);
        assertThat(seen.get("contentType")).startsWith("application/json");
        assertThat(RelayTestClient.signatureMatches(
                RelayTestClient.B_APP_SECRET,
                RelayTestClient.B_APP_KEY,
                seen.get("timestamp"),
                seen.get("nonce"),
                "POST",
                "/api/files",
                null,
                TestSigner.sha256(body),
                seen.get("signature"))).isTrue();
    }

    @Test
    void forwardsThreeHopChainAndAppendsViaPerHop() throws Exception {
        // 假 C：最终目标
        Map<String, String> seenC = new ConcurrentHashMap<>();
        HttpServer fakeC = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeC.createContext("/api/files", exchange -> {
            seenC.put("method", exchange.getRequestMethod());
            seenC.put("uri", exchange.getRequestURI().toString());
            copyHeader(exchange, seenC, "X-App-Key", "appKey");
            copyHeader(exchange, seenC, "X-Device-Id", "deviceId");
            copyHeader(exchange, seenC, "X-Relay-Via", "via");
            copyHeader(exchange, seenC, "X-Relay-Chain", "chain");
            respondJson(exchange, 200, 0, "ok");
        });
        fakeC.start();
        String cUrl = "http://127.0.0.1:" + fakeC.getAddress().getPort();

        // 假 B 收到 /api/relay/api/files 且剩余链 [B,C]：作为中转节点转发给 C
        downstream.createContext("/api/relay/api/files", exchange -> {
            try {
                List<RelayHop> remaining = RelayTestClient.decodeChain(
                        exchange.getRequestHeaders().getFirst("X-Relay-Chain"));
                RelayHop head = remaining.get(0);
                RelayHop next = remaining.get(1);
                List<RelayViaHop> via = new ArrayList<>(RelayTestClient.decodeVia(
                        exchange.getRequestHeaders().getFirst("X-Relay-Via")));
                via.add(new RelayViaHop(head.appKey(), head.name(), head.url()));
                byte[] viaHeader = MAPPER.writeValueAsBytes(via);
                String encodedVia = java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(viaHeader);
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(next.url() + "/api/files" + querySuffix(exchange.getRequestURI().getRawQuery())))
                        .header("X-App-Key", next.appKey())
                        .header("X-Timestamp", String.valueOf(next.timestamp()))
                        .header("X-Nonce", next.nonce())
                        .header("X-Signature", next.signature())
                        .header("X-Device-Id", next.deviceId())
                        .header("X-Relay-Via", encodedVia)
                        .GET()
                        .build();
                HttpResponse<byte[]> cResponse = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofByteArray());
                respond(exchange, cResponse.statusCode(), "application/json", cResponse.body(), null);
            } catch (Exception e) {
                try {
                    respond(exchange, 500, "application/json",
                            ("{\"code\":50000,\"message\":\"" + e + "\"}").getBytes(StandardCharsets.UTF_8),
                            null);
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        });

        HttpResponse<byte[]> response = client.send("GET", "/api/relay/api/files", "keyword=abc",
                null, null, null,
                List.of(RelayTestClient.hop(downstreamUrl, "B", RelayTestClient.B_APP_KEY,
                                RelayTestClient.B_DEVICE_ID)
                        , RelayTestClient.hop(cUrl, "C", RelayTestClient.C_APP_KEY,
                                RelayTestClient.C_DEVICE_ID)));
        fakeC.stop(0);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(seenC.get("uri")).isEqualTo("/api/files?keyword=abc");
        assertThat(seenC.get("appKey")).isEqualTo(RelayTestClient.C_APP_KEY);
        assertThat(seenC.get("deviceId")).isEqualTo(RelayTestClient.C_DEVICE_ID);
        assertThat(seenC).doesNotContainKey("chain");
        assertThat(RelayTestClient.decodeVia(seenC.get("via")))
                .extracting(RelayViaHop::name).containsExactly("A", "B");
    }

    @Test
    void mapsDownstreamErrors() throws Exception {
        HttpResponse<byte[]> auth = forward("GET", "/api/downstream/401", null, null, null, null);
        assertThat(auth.statusCode()).isEqualTo(401);
        assertThat(code(auth)).isEqualTo(40110);
        assertThat(message(auth)).contains("签名无效");

        HttpResponse<byte[]> identity = forward("GET", "/api/downstream/403-identity", null, null, null, null);
        assertThat(identity.statusCode()).isEqualTo(502);
        assertThat(code(identity)).isEqualTo(50212);

        HttpResponse<byte[]> device = forward("GET", "/api/downstream/403-device", null, null, null, null);
        assertThat(device.statusCode()).isEqualTo(403);
        assertThat(code(device)).isEqualTo(40300);

        HttpResponse<byte[]> rateLimited = forward("GET", "/api/downstream/429", null, null, null, null);
        assertThat(rateLimited.statusCode()).isEqualTo(429);
        assertThat(code(rateLimited)).isEqualTo(42900);

        HttpResponse<byte[]> serverError = forward("GET", "/api/downstream/500", null, null, null, null);
        assertThat(serverError.statusCode()).isEqualTo(500);
        assertThat(code(serverError)).isEqualTo(50000);

        HttpResponse<byte[]> business = forward("GET", "/api/downstream/business-404", null, null, null, null);
        assertThat(business.statusCode()).isEqualTo(404);
        assertThat(code(business)).isEqualTo(40400);
        assertThat(message(business)).contains("资源不存在");

        HttpResponse<byte[]> redirect = forward("GET", "/api/downstream/redirect", null, null, null, null);
        assertThat(redirect.statusCode()).isEqualTo(502);
        assertThat(code(redirect)).isEqualTo(50211);

        HttpResponse<byte[]> badResponse = forward("GET", "/api/downstream/bad-response", null, null, null, null);
        assertThat(badResponse.statusCode()).isEqualTo(502);
        assertThat(code(badResponse)).isEqualTo(50211);
    }

    @Test
    void connectionRefusedMapsToUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpResponse<byte[]> response = forwardWithHops("GET", "/api/files", null,
                List.of(RelayTestClient.hop("http://127.0.0.1:" + closedPort, "B",
                        RelayTestClient.B_APP_KEY, RelayTestClient.B_DEVICE_ID)));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(code(response)).isEqualTo(50210);
    }

    @Test
    void timeoutMapsToRelayTimeout() throws Exception {
        HttpResponse<byte[]> response = forward("GET", "/api/slow", null, null, null, null);

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(code(response)).isEqualTo(50410);
    }

    @Test
    void rejectsInvalidPathsAndChains() throws Exception {
        HttpResponse<byte[]> missing = sendRaw("GET", "/api/relay/api/files", null, List.of());
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(code(missing)).isEqualTo(40010);

        HttpResponse<byte[]> nested = forward("GET", "/api/relay/api/files", null, null, null, null);
        assertThat(nested.statusCode()).isEqualTo(400);
        assertThat(code(nested)).isEqualTo(40010);

        // 链头 appKey 与请求标准签名头不一致
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String signature = TestSigner.signature(TestSigner.APP_SECRET,
                TestSigner.canonical(TestSigner.APP_KEY, timestamp, nonce, "GET",
                        "/api/relay/api/files", null, SignatureService.emptyPayloadHash()));
        RelayHop mismatchedHead = new RelayHop("http://127.0.0.1:" + port, "A",
                TestSigner.SECOND_APP_KEY, Long.parseLong(timestamp), nonce, signature,
                RelayTestClient.A_DEVICE_ID);
        HttpResponse<byte[]> mismatch = sendWithChain("GET", "/api/relay/api/files", null, timestamp,
                nonce, signature, List.of(mismatchedHead,
                        RelayTestClient.hop(downstreamUrl, "B", RelayTestClient.B_APP_KEY,
                                RelayTestClient.B_DEVICE_ID)));
        assertThat(mismatch.statusCode()).isEqualTo(400);
        assertThat(code(mismatch)).isEqualTo(40010);
    }

    @Test
    void rejectsSsrfUrls() throws Exception {
        HttpResponse<byte[]> scheme = forwardWithHops("GET", "/api/files", null,
                List.of(RelayTestClient.hop("file:///etc/passwd", "B",
                        RelayTestClient.B_APP_KEY, RelayTestClient.B_DEVICE_ID)));
        assertThat(scheme.statusCode()).isEqualTo(400);
        assertThat(code(scheme)).isEqualTo(40010);

        HttpResponse<byte[]> metadata = forwardWithHops("GET", "/api/files", null,
                List.of(RelayTestClient.hop("http://169.254.169.254/latest/meta-data", "B",
                        RelayTestClient.B_APP_KEY, RelayTestClient.B_DEVICE_ID)));
        assertThat(metadata.statusCode()).isEqualTo(400);
        assertThat(code(metadata)).isEqualTo(40010);

        HttpResponse<byte[]> userInfo = forwardWithHops("GET", "/api/files", null,
                List.of(RelayTestClient.hop("http://user:pass@127.0.0.1:1234", "B",
                        RelayTestClient.B_APP_KEY, RelayTestClient.B_DEVICE_ID)));
        assertThat(userInfo.statusCode()).isEqualTo(400);
        assertThat(code(userInfo)).isEqualTo(40010);
    }

    @Test
    void recordsRelayActionInAudit() throws Exception {
        HttpResponse<byte[]> response = forward("GET", "/api/files", "keyword=abc", null, null, null);
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();

        AuditLogItem item = awaitAudit(requestId);
        assertThat(item.action()).isEqualTo("relay.files.list");
        assertThat(item.path()).isEqualTo("/api/relay/api/files");
        // A 是 root：审计没有来源链
        assertThat(item.relayVia()).isEmpty();
    }

    private AuditLogItem awaitAudit(String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<AuditLogItem> items = auditStore.query(new AuditStore.Query(
                    com.wenshan.upload.support.TestSigner.APP_KEY, 1, 200, null, null,
                    requestId, null, null)).items();
            if (!items.isEmpty()) {
                return items.get(0);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待审计入库 requestId=" + requestId + " 超时");
    }

    // ------------------------------------------------------------------

    private HttpResponse<byte[]> forward(String method, String originalPath, String query, byte[] body,
                                         String contentType, String declaredSha) throws Exception {
        // 最终目标 B：路径就是原始业务路径，用 B 的假 secret 预签（服务端只透传，测试后续复算）
        RelayHop finalHop = RelayTestClient.signedHop(downstreamUrl, "B",
                RelayTestClient.B_APP_KEY, RelayTestClient.B_APP_SECRET, RelayTestClient.B_DEVICE_ID,
                method, originalPath, query, body, declaredSha);
        return client.send(method, "/api/relay" + originalPath, query, body, contentType, declaredSha,
                List.of(finalHop));
    }

    private HttpResponse<byte[]> forwardWithHops(String method, String originalPath, String query,
                                                 List<RelayHop> hops) throws Exception {
        return forwardWithHops(method, originalPath, query, null, null, null, hops);
    }

    private HttpResponse<byte[]> forwardWithHops(String method, String originalPath, String query, byte[] body,
                                                 String contentType, String declaredSha,
                                                 List<RelayHop> hops) throws Exception {
        String relayPath = "/api/relay" + originalPath;
        String effectiveRelayPath = originalPath.startsWith("/api/relay")
                ? "/api/relay" + originalPath : relayPath;
        return client.send(method, effectiveRelayPath, query, body, contentType, declaredSha, hops);
    }

    private HttpResponse<byte[]> sendRaw(String method, String relayPath, String query,
                                         List<RelayHop> chainHops) throws Exception {
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String signature = TestSigner.signature(TestSigner.APP_SECRET,
                TestSigner.canonical(TestSigner.APP_KEY, timestamp, nonce, method, relayPath,
                        query, SignatureService.emptyPayloadHash()));
        return sendWithChain(method, relayPath, query, timestamp, nonce, signature, chainHops);
    }

    private HttpResponse<byte[]> sendWithChain(String method, String relayPath, String query,
                                               String timestamp, String nonce, String signature,
                                               List<RelayHop> chainHops) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(client.url(relayPath, query))
                .header("X-App-Key", TestSigner.APP_KEY)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("X-Device-Id", RelayTestClient.A_DEVICE_ID)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (chainHops != null && !chainHops.isEmpty()) {
            builder.header("X-Relay-Chain", RelayTestClient.encodeChain(chainHops));
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static int code(HttpResponse<byte[]> response) throws Exception {
        return MAPPER.readTree(response.body()).path("code").asInt();
    }

    private static String message(HttpResponse<byte[]> response) throws Exception {
        return MAPPER.readTree(response.body()).path("message").asText();
    }

    private void capture(HttpExchange exchange) throws IOException {
        seen.put("method", exchange.getRequestMethod());
        seen.put("uri", exchange.getRequestURI().toString());
        copyHeader(exchange, seen, "X-App-Key", "appKey");
        copyHeader(exchange, seen, "X-Timestamp", "timestamp");
        copyHeader(exchange, seen, "X-Nonce", "nonce");
        copyHeader(exchange, seen, "X-Signature", "signature");
        copyHeader(exchange, seen, "X-Device-Id", "deviceId");
        copyHeader(exchange, seen, "X-Relay-Via", "via");
        copyHeader(exchange, seen, "X-Relay-Chain", "chain");
        copyHeader(exchange, seen, "X-Content-Sha256", "contentSha");
        copyHeader(exchange, seen, "Content-Type", "contentType");
        seenBody.set(exchange.getRequestBody().readAllBytes());
    }

    private static void respondJson(HttpExchange exchange, int status, int code, String message)
            throws IOException {
        byte[] body = ("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        respond(exchange, status, "application/json", body, null);
    }

    private static void respond(HttpExchange exchange, int status, String contentType,
                                byte[] body, String requestId) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (requestId != null) {
            exchange.getResponseHeaders().set("X-Request-Id", requestId);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } finally {
            exchange.close();
        }
    }

    private static void copyHeader(HttpExchange exchange, Map<String, String> target,
                                   String header, String key) {
        String value = exchange.getRequestHeaders().getFirst(header);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String querySuffix(String rawQuery) {
        return rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery;
    }
}
