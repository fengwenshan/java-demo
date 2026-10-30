package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.dto.CopyRequest;
import com.wenshan.upload.dto.MoveRequest;
import com.wenshan.upload.dto.RenameRequest;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作审计集成测试：成功/失败/重放/未知路径/脱敏/终端命令/上传下载等真实请求写入 JSONL，
 * requestId 与响应头一致，业务错误 code/message 通过 GlobalExceptionHandler request attr 捕获。
 */
@SpringBootTest(properties = "app.test.suite=audit")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditIntegrationTest extends IntegrationTestSupport {

    private static final Path AUDIT_DIR;

    static {
        try {
            AUDIT_DIR = Files.createTempDirectory("audit-integration-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void auditDir(DynamicPropertyRegistry registry) {
        registry.add("app.audit.dir", AUDIT_DIR::toString);
    }

    private static final String LIST_PATH = "/api/files";
    private static final String DIR_PATH = "/api/files/dir";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final String UPLOAD_PATH = "/api/files/upload";
    private static final String COPY_PATH = "/api/files/copy";
    private static final String MOVE_PATH = "/api/files/move";
    private static final String RENAME_PATH = "/api/files/rename";
    private static final String EXTRACT_PATH = "/api/files/extract";
    private static final String CONTENT_PATH = "/api/files/content";
    private static final String TERMINAL_PATH = "/api/terminal/exec";

    @Test
    void successfulRequestWritesAuditLineWithMatchingRequestId() throws Exception {
        ResultActions result = mockMvc.perform(signedGetQuery(LIST_PATH, Map.of()))
                .andExpect(status().isOk());
        String requestId = responseRequestId(result);
        assertThat(requestId).isNotBlank();

        JsonNode event = awaitAudit(requestId);
        assertThat(event.path("requestId").asText()).isEqualTo(requestId);
        assertThat(event.path("appKey").asText()).isEqualTo(TestSigner.APP_KEY);
        assertThat(event.path("clientName").asText()).isNotBlank();
        assertThat(event.path("clientIp").asText()).isNotBlank();
        assertThat(event.path("method").asText()).isEqualTo("GET");
        assertThat(event.path("path").asText()).isEqualTo(LIST_PATH);
        assertThat(event.path("action").asText()).isEqualTo("files.list");
        assertThat(event.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(event.path("success").asBoolean()).isTrue();
        assertThat(event.path("error").isNull()).isTrue();
        assertThat(event.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(OffsetDateTime.parse(event.path("ts").asText())).isNotNull();
    }

    @Test
    void fileOperationsRecordExpectedActionsAndParameters() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] fileContent = "audit-file-content".getBytes(StandardCharsets.UTF_8);

        // upload
        ResultActions upload = upload("audit.txt", fileContent);
        JsonNode uploadEvent = awaitAudit(responseRequestId(upload));
        assertThat(uploadEvent.path("action").asText()).isEqualTo("upload");
        assertThat(uploadEvent.path("contentSha256").asText()).isEqualTo(TestSigner.sha256(fileContent));
        assertThat(uploadEvent.path("body").isNull()).isTrue();

        // download
        ResultActions download = mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("path", "docs/audit.txt")))
                .andExpect(status().isOk());
        JsonNode downloadEvent = awaitAudit(responseRequestId(download));
        assertThat(downloadEvent.path("action").asText()).isEqualTo("download");
        assertThat(downloadEvent.path("pathParam").asText()).isEqualTo("docs/audit.txt");

        // copy
        ResultActions copy = mockMvc.perform(signedJsonPost(COPY_PATH, new CopyRequest(null, "docs/audit.txt")))
                .andExpect(status().isOk());
        JsonNode copyEvent = awaitAudit(responseRequestId(copy));
        assertThat(copyEvent.path("action").asText()).isEqualTo("copy");
        assertThat(copyEvent.path("pathParam").asText()).isEqualTo("docs/audit.txt");

        // move
        Files.createDirectories(storagePaths.root().resolve("docs/sub"));
        ResultActions move = mockMvc.perform(signedJsonPost(MOVE_PATH,
                        new MoveRequest(null, "docs/audit_副本.txt", null, "docs/sub")))
                .andExpect(status().isOk());
        JsonNode moveEvent = awaitAudit(responseRequestId(move));
        assertThat(moveEvent.path("action").asText()).isEqualTo("move");
        assertThat(moveEvent.path("targetDir").asText()).isEqualTo("docs/sub");

        // rename
        ResultActions rename = mockMvc.perform(signedJsonPost(RENAME_PATH,
                        new RenameRequest(null, "docs/sub/audit_副本.txt", "renamed.txt")))
                .andExpect(status().isOk());
        JsonNode renameEvent = awaitAudit(responseRequestId(rename));
        assertThat(renameEvent.path("action").asText()).isEqualTo("rename");
        assertThat(renameEvent.path("body").asText()).contains("renamed.txt");

        // delete
        ResultActions deleteResult = mockMvc.perform(signedDeleteQuery(Map.of("path", "docs/sub/renamed.txt")))
                .andExpect(status().isOk());
        JsonNode deleteEvent = awaitAudit(responseRequestId(deleteResult));
        assertThat(deleteEvent.path("action").asText()).isEqualTo("delete");

        // extract
        writeZip(storagePaths.root().resolve("docs/pack.zip"));
        ResultActions extract = mockMvc.perform(signedJsonPost(EXTRACT_PATH, new ExtractBody("docs/pack.zip")))
                .andExpect(status().isOk());
        JsonNode extractEvent = awaitAudit(responseRequestId(extract));
        assertThat(extractEvent.path("action").asText()).isEqualTo("extract");
        assertThat(extractEvent.path("pathParam").asText()).isEqualTo("docs/pack.zip");

        // terminal exec（命令完整记录）
        ResultActions terminal = mockMvc.perform(signedJsonPost(TERMINAL_PATH,
                        new TerminalBody("audit-session-" + System.nanoTime(), "echo audit-terminal")))
                .andExpect(status().isOk());
        JsonNode terminalEvent = awaitAudit(responseRequestId(terminal));
        assertThat(terminalEvent.path("action").asText()).isEqualTo("terminal.exec");
        assertThat(terminalEvent.path("terminalCommand").asText()).isEqualTo("echo audit-terminal");
        assertThat(terminalEvent.path("sessionId").asText()).isNotBlank();
    }

    @Test
    void rejectedRequestsAreAuditedWithStatusAndError() throws Exception {
        // 错误签名
        ResultActions badSignature = mockMvc.perform(TestSigner.withSignature(get(LIST_PATH), "GET", LIST_PATH,
                        null, SignatureService.emptyPayloadHash(), TestSigner.APP_KEY, TestSigner.WRONG_SECRET,
                        TestSigner.now(), TestSigner.newNonce(), null, null))
                .andExpect(status().isUnauthorized());
        JsonNode badSignatureEvent = awaitAudit(responseRequestId(badSignature));
        assertThat(badSignatureEvent.path("httpStatus").asInt()).isEqualTo(401);
        assertThat(badSignatureEvent.path("success").asBoolean()).isFalse();
        assertThat(badSignatureEvent.path("error").asText()).contains("40100");

        // 时间戳过期
        ResultActions expired = mockMvc.perform(TestSigner.withSignature(get(LIST_PATH), "GET", LIST_PATH,
                        null, SignatureService.emptyPayloadHash(), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                        String.valueOf(System.currentTimeMillis() - 10 * 60 * 1000L), TestSigner.newNonce(), null, null))
                .andExpect(status().isUnauthorized());
        JsonNode expiredEvent = awaitAudit(responseRequestId(expired));
        assertThat(expiredEvent.path("httpStatus").asInt()).isEqualTo(401);
        assertThat(expiredEvent.path("error").asText()).contains("40101");

        // nonce 重放：第一次成功、第二次 40102
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        MockHttpServletRequestBuilder replayBuilder = TestSigner.withSignature(get(LIST_PATH), "GET", LIST_PATH,
                null, SignatureService.emptyPayloadHash(), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                timestamp, nonce, null, null);
        mockMvc.perform(replayBuilder).andExpect(status().isOk());
        ResultActions replay = mockMvc.perform(TestSigner.withSignature(get(LIST_PATH), "GET", LIST_PATH,
                        null, SignatureService.emptyPayloadHash(), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                        timestamp, nonce, null, null))
                .andExpect(status().isUnauthorized());
        JsonNode replayEvent = awaitAudit(responseRequestId(replay));
        assertThat(replayEvent.path("error").asText()).contains("40102");

        // 未知路径：签名通过但 404，action fallback
        ResultActions unknown = mockMvc.perform(TestSigner.signedGet("/api/unknown/thing", null))
                .andExpect(status().isNotFound());
        JsonNode unknownEvent = awaitAudit(responseRequestId(unknown));
        assertThat(unknownEvent.path("action").asText()).isEqualTo("GET /api/unknown/thing");
        assertThat(unknownEvent.path("httpStatus").asInt()).isEqualTo(404);
        assertThat(unknownEvent.path("success").asBoolean()).isFalse();
        assertThat(unknownEvent.path("error").asText()).contains("40400");
    }

    @Test
    void sensitiveDataIsMaskedAndNeverWritten() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        Files.writeString(storagePaths.root().resolve("docs/audit.txt"), "old", StandardCharsets.UTF_8);

        // content.save：content 正文脱敏为 ***
        ResultActions save = mockMvc.perform(TestSigner.signedJsonPut(put(CONTENT_PATH), CONTENT_PATH,
                        objectMapper.writeValueAsBytes(new ContentSaveBody(null, "docs/audit.txt",
                                "TOP-SECRET-CONTENT", null))))
                .andExpect(status().isOk());
        JsonNode saveEvent = awaitAudit(responseRequestId(save));
        String saveLine = saveEvent.toString();
        assertThat(saveLine).doesNotContain("TOP-SECRET-CONTENT");
        assertThat(saveEvent.path("body").asText()).contains("\"content\":\"***\"");

        // query token 脱敏
        ResultActions withToken = mockMvc.perform(signedGetQuery(LIST_PATH, Map.of("token", "QUERY-SECRET")))
                .andExpect(status().isOk());
        JsonNode tokenEvent = awaitAudit(responseRequestId(withToken));
        assertThat(tokenEvent.path("query").asText()).contains("token=***").doesNotContain("QUERY-SECRET");

        // multipart 上传文件正文不落审计
        byte[] secretContent = "FILE-SECRET-CONTENT".getBytes(StandardCharsets.UTF_8);
        ResultActions upload = upload("secret.txt", secretContent);
        JsonNode uploadEvent = awaitAudit(responseRequestId(upload));
        assertThat(uploadEvent.toString()).doesNotContain("FILE-SECRET-CONTENT");
        assertThat(uploadEvent.path("body").isNull()).isTrue();
        assertThat(uploadEvent.path("contentSha256").asText()).isEqualTo(TestSigner.sha256(secretContent));

        // 终端命令完整记录（非敏感字段，不脱敏）
        ResultActions terminal = mockMvc.perform(signedJsonPost(TERMINAL_PATH,
                        new TerminalBody("audit-secret-session", "echo AUDIT-VISIBLE-COMMAND")))
                .andExpect(status().isOk());
        JsonNode terminalEvent = awaitAudit(responseRequestId(terminal));
        assertThat(terminalEvent.path("terminalCommand").asText()).isEqualTo("echo AUDIT-VISIBLE-COMMAND");
        assertThat(terminalEvent.path("body").asText()).contains("AUDIT-VISIBLE-COMMAND");

        // 不记录签名/nonce 等请求头字段
        assertThat(saveEvent.toString()).doesNotContain("X-Signature", "X-Nonce", "signature=", "nonce=");
    }

    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder signedGetQuery(String path, Map<String, String> params) {
        MockHttpServletRequestBuilder builder = get(path);
        List<String> rawPairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
            rawPairs.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return TestSigner.signedGet(builder, path, String.join("&", rawPairs));
    }

    private MockHttpServletRequestBuilder signedDeleteQuery(Map<String, String> params) {
        MockHttpServletRequestBuilder builder = delete(LIST_PATH);
        List<String> rawPairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
            rawPairs.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return TestSigner.withSignature(builder, "DELETE", LIST_PATH, String.join("&", rawPairs),
                SignatureService.emptyPayloadHash(), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                TestSigner.now(), TestSigner.newNonce(), null, null);
    }

    private MockHttpServletRequestBuilder signedJsonPost(String path, Object body) throws Exception {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        return TestSigner.signedJsonPost(post(path), path, bytes);
    }

    private ResultActions upload(String fileName, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        builder.param("name", fileName);
        builder.param("dir", "docs");
        return mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk());
    }

    private static String responseRequestId(ResultActions actions) {
        String requestId = actions.andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
        return requestId;
    }

    private JsonNode awaitAudit(String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Stream<Path> files = Files.list(AUDIT_DIR)) {
                for (Path file : files.filter(item -> item.getFileName().toString().startsWith("audit-")).toList()) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        JsonNode node = objectMapper.readTree(line);
                        if (requestId.equals(node.path("requestId").asText())) {
                            return node;
                        }
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待审计日志 requestId=" + requestId + " 超时");
    }

    private static void writeZip(Path zipPath) throws IOException {
        Files.createDirectories(zipPath.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipPath), StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("a.txt"));
            out.write("zip-a".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    record ExtractBody(String path) {
    }

    record TerminalBody(String sessionId, String command) {
    }

    record ContentSaveBody(String root, String path, String content, String expectedSha256) {
    }
}
