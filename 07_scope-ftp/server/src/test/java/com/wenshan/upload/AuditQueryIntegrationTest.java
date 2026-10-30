package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.audit.AuditEvent;
import com.wenshan.upload.audit.AuditStore;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.dto.AuditLogItem;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/audit/logs 集成测试：签名校验/强制 appKey/分页/过滤/隔离/参数上限/查询自身审计/JSONL 一致。
 */
@SpringBootTest(properties = "app.test.suite=audit-query")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditQueryIntegrationTest extends IntegrationTestSupport {

    private static final Path AUDIT_DIR;

    static {
        try {
            AUDIT_DIR = Files.createTempDirectory("audit-query-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void auditDir(DynamicPropertyRegistry registry) {
        registry.add("app.audit.dir", AUDIT_DIR::toString);
    }

    @Autowired
    private AuditStore auditStore;

    @Test
    void signedRequestsCanBeQueriedWithPaginationAndFilters() throws Exception {
        // 产生列表 + 上传两条成功审计
        String listRequestId = requestId(mockMvc.perform(TestSigner.signedGet("/api/files", null))
                .andExpect(status().isOk()));
        awaitStored(listRequestId);

        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] content = "query-api-content".getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder uploadBuilder = multipart("/api/files/upload")
                .file(new MockMultipartFile("file", "query.txt", "application/octet-stream", content));
        uploadBuilder.param("name", "query.txt");
        uploadBuilder.param("dir", "docs");
        String uploadRequestId = requestId(mockMvc.perform(
                        TestSigner.signedMultipart(uploadBuilder, "/api/files/upload", content, null))
                .andExpect(status().isOk()));
        awaitStored(uploadRequestId);

        // 默认分页 + 完整字段
        JsonNode page = logsData(Map.of("page", "1", "size", "1"));
        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(page.path("items")).hasSize(1);
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("id").asLong()).isPositive();
        assertThat(item.path("appKey").asText()).isEqualTo(TestSigner.APP_KEY);
        assertThat(item.path("clientName").asText()).isNotBlank();
        assertThat(item.path("clientIp").asText()).isNotBlank();
        assertThat(item.path("ts").asText()).isNotBlank();
        assertThat(OffsetDateTime.parse(item.path("ts").asText())).isNotNull();
        assertThat(item.path("method").asText()).isNotBlank();
        assertThat(item.path("path").asText()).startsWith("/api/");
        assertThat(item.path("httpStatus").asInt()).isPositive();
        assertThat(item.path("success").isBoolean()).isTrue();

        // action 精确过滤
        JsonNode uploads = logsData(Map.of("action", "upload", "size", "200"));
        assertThat(uploads.path("items")).isNotEmpty();
        assertThat(uploads.path("items")).allSatisfy(node ->
                assertThat(node.path("action").asText()).isEqualTo("upload"));
        assertThat(requestIds(uploads)).contains(uploadRequestId);
        assertThat(uploads.path("items")).anySatisfy(node -> {
            if (uploadRequestId.equals(node.path("requestId").asText())) {
                // multipart body 不缓存，controller 回填的实际文件名必须进入审计，供列表展示。
                assertThat(node.path("fileName").asText()).isEqualTo("query.txt");
            }
        });

        // category=operation：只返回服务器实际操作，不包含列表等接口请求
        JsonNode operations = logsData(Map.of("category", "operation", "size", "200"));
        assertThat(operations.path("items")).isNotEmpty();
        assertThat(operations.path("items")).allSatisfy(node ->
                assertThat(node.path("action").asText()).isIn(
                        "upload", "upload.complete", "download", "download.byId", "delete",
                        "rename", "copy", "move", "extract", "content.read", "content.save",
                        "terminal.exec"));
        assertThat(requestIds(operations)).contains(uploadRequestId);
        assertThat(requestIds(operations)).doesNotContain(listRequestId);

        // category=connection：连接验证/激活单独一类
        JsonNode connections = logsData(Map.of("category", "connection", "size", "200"));
        assertThat(connections.path("items")).allSatisfy(node ->
                assertThat(node.path("action").asText()).isIn("auth.verify", "auth.activate"));

        // 连接失败（错误签名）也必须进入连接日志，方便判断是密钥/时间/网络问题
        ResultActions badVerify = mockMvc.perform(TestSigner.withSignature(get("/api/auth/verify"),
                        "GET", "/api/auth/verify", null, SignatureService.emptyPayloadHash(),
                        TestSigner.APP_KEY, TestSigner.WRONG_SECRET, TestSigner.now(),
                        TestSigner.newNonce(), null, null))
                .andExpect(status().isUnauthorized());
        String badVerifyId = requestId(badVerify);
        awaitStored(badVerifyId);
        JsonNode failedConnections = logsData(Map.of("category", "connection", "size", "200"));
        assertThat(requestIds(failedConnections)).contains(badVerifyId);
        assertThat(failedConnections.path("items")).anySatisfy(node -> {
            if (badVerifyId.equals(node.path("requestId").asText())) {
                assertThat(node.path("action").asText()).isEqualTo("auth.verify");
                assertThat(node.path("success").asBoolean()).isFalse();
                assertThat(node.path("error").asText()).isNotBlank();
            }
        });

        // category=both：客户端日志抽屉合并视图，同时包含实际操作与失败连接
        JsonNode both = logsData(Map.of("category", "both", "size", "200"));
        assertThat(requestIds(both)).contains(uploadRequestId, badVerifyId);
        assertThat(requestIds(both)).doesNotContain(listRequestId);
        assertThat(both.path("items")).allSatisfy(node ->
                assertThat(node.path("action").asText()).isIn(
                        "upload", "upload.complete", "download", "download.byId", "delete",
                        "rename", "copy", "move", "extract", "content.read", "content.save",
                        "terminal.exec", "auth.verify", "auth.activate"));

        // keyword 模糊匹配 request_id
        JsonNode byRequestId = logsData(Map.of("keyword", listRequestId));
        assertThat(requestIds(byRequestId)).contains(listRequestId);

        // success=false：错误签名请求也会入库
        ResultActions bad = mockMvc.perform(TestSigner.withSignature(get("/api/files"), "GET", "/api/files",
                        null, SignatureService.emptyPayloadHash(), TestSigner.APP_KEY,
                        TestSigner.WRONG_SECRET, TestSigner.now(), TestSigner.newNonce(), null, null))
                .andExpect(status().isUnauthorized());
        String badRequestId = requestId(bad);
        awaitStored(badRequestId);
        JsonNode failures = logsData(Map.of("success", "false", "size", "200"));
        assertThat(requestIds(failures)).contains(badRequestId);
        assertThat(failures.path("items")).allSatisfy(node ->
                assertThat(node.path("success").asBoolean()).isFalse());
    }

    @Test
    void onlyCurrentAppKeyIsReturnedAndUnsignedIsRejected() throws Exception {
        // 直接写入一条其他 appKey 的记录，查询接口不能返回它
        String otherRequestId = "other-app-" + System.nanoTime();
        auditStore.insert(event(otherRequestId, "other-app"));

        JsonNode data = logsData(Map.of("keyword", otherRequestId));
        assertThat(data.path("total").asLong()).isZero();

        // 即使 query 里伪造 appKey 参数，也只返回当前签名 appKey
        JsonNode all = logsData(Map.of("appKey", "other-app", "size", "200"));
        assertThat(all.path("items")).allSatisfy(node ->
                assertThat(node.path("appKey").asText()).isEqualTo(TestSigner.APP_KEY));

        // 未签名访问：401 + 40100，且不会进入查询逻辑
        mockMvc.perform(get("/api/audit/logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_SIGNATURE.getCode()));
    }

    @Test
    void pageAndTimeParametersAreValidated() throws Exception {
        int badRequest = ErrorCode.BAD_REQUEST.getCode();
        assertThat(logsCode(Map.of("page", "0"))).isEqualTo(badRequest);
        assertThat(logsCode(Map.of("size", "0"))).isEqualTo(badRequest);
        assertThat(logsCode(Map.of("size", "201"))).isEqualTo(badRequest);
        assertThat(logsCode(Map.of("category", "unknown"))).isEqualTo(badRequest);
        assertThat(logsCode(Map.of("from", "not-a-time"))).isEqualTo(badRequest);
        assertThat(logsCode(Map.of("from", "2026-10-30T02:00:00Z", "to", "2026-10-30T01:00:00Z")))
                .isEqualTo(badRequest);

        JsonNode max = logsData(Map.of("size", "200"));
        assertThat(max.path("size").asInt()).isEqualTo(200);
        assertThat(max.path("page").asInt()).isEqualTo(1);
    }

    @Test
    void timeRangeFiltersAndExistingJsonlIsNotImportedIntoDb() throws Exception {
        String requestId = requestId(mockMvc.perform(TestSigner.signedGet("/api/files", null))
                .andExpect(status().isOk()));
        awaitStored(requestId);

        String future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertThat(logsData(Map.of("from", future)).path("total").asLong()).isZero();
        assertThat(logsData(Map.of("to", past)).path("total").asLong()).isZero();
        assertThat(logsData(Map.of("from", past, "to", future)).path("total").asLong())
                .isGreaterThanOrEqualTo(1);

        // 旧 JSONL 保留但不导入新 DB：不查询到，原文件仍在
        Path legacyFile = AUDIT_DIR.resolve("audit-2020-01-01.jsonl");
        String legacyRequestId = "legacy-jsonl-" + System.nanoTime();
        Files.writeString(legacyFile, """
                {"ts":"2020-01-01T00:00:00Z","requestId":"%s","appKey":"demo-app","action":"files.list","path":"/api/files","success":true,"httpStatus":200}
                """.formatted(legacyRequestId));
        assertThat(logsData(Map.of("keyword", legacyRequestId)).path("total").asLong()).isZero();
        assertThat(legacyFile).isRegularFile();
    }

    @Test
    void queryItselfIsAuditedAndJsonlMatchesDatabase() throws Exception {
        String listRequestId = requestId(mockMvc.perform(TestSigner.signedGet("/api/files", null))
                .andExpect(status().isOk()));
        awaitStored(listRequestId);

        ResultActions query = signedLogs(Map.of("size", "10")).andExpect(status().isOk());
        String queryRequestId = requestId(query);
        awaitStored(queryRequestId);

        AuditLogItem event = auditStore.query(new AuditStore.Query(
                        TestSigner.APP_KEY, 1, 200, null, null, queryRequestId, null, null))
                .items().stream().findFirst().orElseThrow();
        assertThat(event.action()).isEqualTo("audit.list");
        assertThat(event.path()).isEqualTo("/api/audit/logs");
        assertThat(event.success()).isTrue();
        assertThat(event.httpStatus()).isEqualTo(200);

        // JSONL 与 DB 内容一致（JSONL 先写、DB 后写：awaitStored 后 JSONL 已完成）
        String jsonLine = awaitJsonlLine(queryRequestId);
        JsonNode jsonEvent = objectMapper.readTree(jsonLine);
        assertThat(jsonEvent.path("requestId").asText()).isEqualTo(event.requestId());
        assertThat(jsonEvent.path("action").asText()).isEqualTo(event.action());
        assertThat(jsonEvent.path("path").asText()).isEqualTo(event.path());
        assertThat(jsonEvent.path("httpStatus").asInt()).isEqualTo(event.httpStatus());
    }

    // ------------------------------------------------------------------

    private ResultActions signedLogs(Map<String, String> params) throws Exception {
        List<String> pairs = new ArrayList<>();
        MockHttpServletRequestBuilder builder = get("/api/audit/logs");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
            pairs.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return mockMvc.perform(TestSigner.signedGet(builder, "/api/audit/logs", String.join("&", pairs)));
    }

    private JsonNode logsData(Map<String, String> params) throws Exception {
        return body(signedLogs(params).andExpect(status().isOk())).path("data");
    }

    private int logsCode(Map<String, String> params) throws Exception {
        return body(signedLogs(params)).path("code").asInt();
    }

    private static String requestId(ResultActions actions) {
        String value = actions.andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(value).isNotBlank();
        return value;
    }

    private static List<String> requestIds(JsonNode data) {
        List<String> ids = new ArrayList<>();
        data.path("items").forEach(item -> ids.add(item.path("requestId").asText()));
        return ids;
    }

    private void awaitStored(String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (auditStore.query(new AuditStore.Query(TestSigner.APP_KEY, 1, 200,
                    null, null, requestId, null, null)).total() > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待审计入库 requestId=" + requestId + " 超时");
    }

    private String awaitJsonlLine(String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Stream<Path> files = Files.list(AUDIT_DIR)) {
                for (Path file : files.filter(item -> item.getFileName().toString().startsWith("audit-")
                        && item.getFileName().toString().endsWith(".jsonl")).toList()) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        JsonNode node = objectMapper.readTree(line);
                        if (requestId.equals(node.path("requestId").asText())) {
                            return line;
                        }
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待 JSONL requestId=" + requestId + " 超时");
    }

    private static AuditEvent event(String requestId, String appKey) {
        String ts = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new AuditEvent(ts, requestId, appKey, "其他客户端", null, "127.0.0.1",
                "GET", "/api/files", null, "files.list", null, null, null, null, null,
                null, null, null, 0L, 12L, 1L, 200, true, null, "JUnit", null);
    }
}
