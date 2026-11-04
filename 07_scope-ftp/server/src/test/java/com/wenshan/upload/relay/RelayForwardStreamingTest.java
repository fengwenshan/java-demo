package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wenshan.upload.support.RelayTestClient;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式 body 集成测试（真实端口）：8MB octet 分片、8MB 下载、multipart 上传都不经过
 * CachedBodyHttpServletRequest，而是直接桥接原始 InputStream / 响应 InputStream。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.relay.enabled=true",
                "app.relay.allow-loopback=true"
        })
@ActiveProfiles("test")
class RelayForwardStreamingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int EIGHT_MB = 8 * 1024 * 1024;

    @LocalServerPort
    int port;

    private HttpServer downstream;
    private String downstreamUrl;
    private RelayTestClient client;
    private final AtomicInteger receivedChunkBytes = new AtomicInteger(-1);
    private final AtomicReference<byte[]> receivedMultipartBody = new AtomicReference<>(new byte[0]);
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    @BeforeEach
    void startDownstream() throws IOException {
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/api/files/upload/up-1/chunk", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                receivedChunkBytes.set(in.readAllBytes().length);
            }
            respondJson(exchange, 200);
        });
        downstream.createContext("/api/files/upload", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                receivedMultipartBody.set(in.readAllBytes());
            }
            seen.put("contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            seen.put("contentSha", String.valueOf(exchange.getRequestHeaders().getFirst("X-Content-Sha256")));
            respondJson(exchange, 200);
        });
        downstream.createContext("/api/files/abc/download", exchange -> {
            byte[] payload = new byte[EIGHT_MB];
            Arrays.fill(payload, (byte) 7);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"abc.bin\"");
            exchange.getResponseHeaders().set("X-Content-Sha256", TestSigner.sha256(payload));
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            } finally {
                exchange.close();
            }
        });
        downstream.start();
        downstreamUrl = "http://127.0.0.1:" + downstream.getAddress().getPort();
        client = new RelayTestClient(port);
        receivedChunkBytes.set(-1);
        receivedMultipartBody.set(new byte[0]);
        seen.clear();
    }

    @AfterEach
    void stopDownstream() {
        downstream.stop(0);
    }

    @Test
    void streamsEightMegabyteOctetChunkWithoutBuffering() throws Exception {
        byte[] chunk = new byte[EIGHT_MB];
        Arrays.fill(chunk, (byte) 9);
        String sha = TestSigner.sha256(chunk);
        String path = "/api/files/upload/up-1/chunk";
        String query = "index=0";
        RelayHop hop = RelayTestClient.signedHop(downstreamUrl, "B",
                RelayTestClient.B_APP_KEY, RelayTestClient.B_APP_SECRET, RelayTestClient.B_DEVICE_ID,
                "PUT", path, query, chunk, sha);

        HttpResponse<byte[]> response = client.send("PUT", "/api/relay" + path, query, chunk,
                "application/octet-stream", sha, List.of(hop));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(receivedChunkBytes.get()).isEqualTo(EIGHT_MB);
    }

    @Test
    void streamsEightMegabyteDownload() throws Exception {
        String path = "/api/files/abc/download";
        RelayHop hop = RelayTestClient.signedHop(downstreamUrl, "B",
                RelayTestClient.B_APP_KEY, RelayTestClient.B_APP_SECRET, RelayTestClient.B_DEVICE_ID,
                "GET", path, null, null, null);

        HttpResponse<byte[]> response = client.send("GET", "/api/relay" + path, null,
                null, null, null, List.of(hop));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(EIGHT_MB);
        assertThat(response.body()[0]).isEqualTo((byte) 7);
        assertThat(response.body()[EIGHT_MB / 2]).isEqualTo((byte) 7);
        assertThat(response.body()[EIGHT_MB - 1]).isEqualTo((byte) 7);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).contains("application/octet-stream"));
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(value -> assertThat(value).contains("abc.bin"));
    }

    @Test
    void streamsMultipartBodyAndKeepsBoundary() throws Exception {
        String boundary = "----relayTestBoundary";
        String fileContent = "hello relay multipart";
        byte[] multipartBody = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + fileContent + "\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        String sha = TestSigner.sha256(fileContent.getBytes(StandardCharsets.UTF_8));
        String path = "/api/files/upload";
        RelayHop hop = RelayTestClient.signedHop(downstreamUrl, "B",
                RelayTestClient.B_APP_KEY, RelayTestClient.B_APP_SECRET, RelayTestClient.B_DEVICE_ID,
                "POST", path, null, null, sha);

        HttpResponse<byte[]> response = client.send("POST", "/api/relay" + path, null, multipartBody,
                "multipart/form-data; boundary=" + boundary, sha, List.of(hop));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(receivedMultipartBody.get()).isEqualTo(multipartBody);
        assertThat(seen.get("contentType")).contains("multipart/form-data").contains(boundary);
        assertThat(seen.get("contentSha")).isEqualTo(sha);
        assertThat(MAPPER.readTree(response.body()).path("code").asInt()).isZero();
    }

    private static void respondJson(HttpExchange exchange, int status) throws IOException {
        byte[] body = "{\"code\":0,\"message\":\"ok\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } finally {
            exchange.close();
        }
    }
}
