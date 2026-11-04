package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.audit.AuditStore;
import com.wenshan.upload.audit.RelayViaHop;
import com.wenshan.upload.dto.AuditLogItem;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 最终目标（B/C）的来源审计：解析 X-Relay-Via → AuditEvent/AuditLogItem.relayVia，
 * JSONL 与 SQLite 双写一致，查询接口返回来源链。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RelayAuditIntegrationTest extends IntegrationTestSupport {

    private static final String VIA_HEADER = viaHeader(
            new RelayViaHop("a-app-fake", "A", "http://10.0.0.1:8080"),
            new RelayViaHop("b-app-fake", "B", "http://10.0.0.2:8080"));

    @Autowired
    private AuditStore auditStore;

    @Test
    void finalTargetAuditRecordsRelayViaInSqliteAndJsonl() throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/files").header("X-Relay-Via", VIA_HEADER);
        ResultActions result = mockMvc.perform(TestSigner.signedGet(builder, "/api/files", null))
                .andExpect(status().isOk());
        String requestId = result.andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();

        AuditLogItem item = awaitItem(requestId);
        assertThat(item.action()).isEqualTo("files.list");
        assertThat(item.path()).isEqualTo("/api/files");
        assertThat(item.relayVia()).extracting(RelayViaHop::name).containsExactly("A", "B");
        assertThat(item.relayVia().get(0).appKey()).isEqualTo("a-app-fake");

        JsonNode jsonLine = awaitJsonl(requestId);
        JsonNode relayVia = jsonLine.path("relayVia");
        assertThat(relayVia.isArray()).isTrue();
        assertThat(relayVia).hasSize(2);
        assertThat(relayVia.get(0).path("name").asText()).isEqualTo("A");
        assertThat(relayVia.get(1).path("url").asText()).isEqualTo("http://10.0.0.2:8080");

        // 查询接口返回 relayVia（客户端日志列表/详情显示来源链）
        JsonNode data = body(mockMvc.perform(TestSigner.signedGet(
                        get("/api/audit/logs").queryParam("keyword", requestId).queryParam("size", "10"),
                        "/api/audit/logs", "keyword=" + requestId + "&size=10"))
                .andExpect(status().isOk())).path("data");
        assertThat(data.path("items").get(0).path("relayVia").get(1).path("name").asText())
                .isEqualTo("B");
    }

    @Test
    void corruptedViaHeaderDoesNotBreakAuditOrRequest() throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/files").header("X-Relay-Via", "not-$base64");
        ResultActions result = mockMvc.perform(TestSigner.signedGet(builder, "/api/files", null))
                .andExpect(status().isOk());
        String requestId = result.andReturn().getResponse().getHeader("X-Request-Id");

        AuditLogItem item = awaitItem(requestId);
        assertThat(item.relayVia()).isEmpty();
    }

    private AuditLogItem awaitItem(String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<AuditLogItem> items = auditStore.query(new AuditStore.Query(
                    TestSigner.APP_KEY, 1, 200, null, null, requestId, null, null)).items();
            if (!items.isEmpty()) {
                return items.get(0);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待审计入库 requestId=" + requestId + " 超时");
    }

    private JsonNode awaitJsonl(String requestId) throws Exception {
        Path dir = Path.of(properties.getAudit().getDir()).toAbsolutePath().normalize();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(item -> item.getFileName().toString().startsWith("audit-")
                            && item.getFileName().toString().endsWith(".jsonl")).toList()) {
                        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                            JsonNode node = objectMapper.readTree(line);
                            if (requestId.equals(node.path("requestId").asText())) {
                                return node;
                            }
                        }
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待 JSONL requestId=" + requestId + " 超时");
    }

    private static String viaHeader(RelayViaHop... hops) {
        try {
            byte[] json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(List.of(hops));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
