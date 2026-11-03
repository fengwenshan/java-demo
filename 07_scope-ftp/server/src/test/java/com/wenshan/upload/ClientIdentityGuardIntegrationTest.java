package com.wenshan.upload;

import com.wenshan.upload.security.ClientGuardService;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户端身份强制校验：MAC/IP 两张黑名单、缺身份封禁、verify→activate 顺序校验、
 * 手动删库立即解封（任一条命中仍拦截）。
 */
@SpringBootTest(properties = {
        "app.test.suite=client-guard",
        "app.security.client-identity-enforced=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientIdentityGuardIntegrationTest extends IntegrationTestSupport {

    private static final String MAC_A = "AA:BB:CC:00:00:01";
    private static final String MAC_B = "AA:BB:CC:00:00:02";
    private static final String MAC_C = "AA:BB:CC:00:00:03";
    private static final String MAC_D = "AA:BB:CC:00:00:04";
    private static final String MAC_E = "AA:BB:CC:00:00:05";

    @Autowired
    protected ClientGuardService guardService;

    @BeforeEach
    void clearClientGuardState() throws Exception {
        Path db = storagePaths.metaDir().resolve("metadata.sqlite3");
        if (!Files.isRegularFile(db)) {
            return;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM client_blacklist_ip");
            statement.executeUpdate("DELETE FROM client_blacklist_mac");
            statement.executeUpdate("DELETE FROM client_verify_state");
        }
    }

    @Test
    void missingMacBansIpAndBlocksEverythingFromIt() throws Exception {
        String ip = "203.0.113.10";

        // 缺 X-Device-Mac：直接失败并写 IP 黑名单
        mockMvc.perform(withClient(get("/api/health"), ip, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40312));
        assertThat(blacklistContains("client_blacklist_ip", "ip", ip)).isTrue();

        // 同一 IP 补上合法 MAC 也继续被 IP 黑名单拦截
        mockMvc.perform(withClient(get("/api/health"), ip, MAC_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40310));

        // 其它 IP 不受影响
        mockMvc.perform(withClient(get("/api/health"), "203.0.113.11", MAC_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void bannedMacBlocksFromAnyIp() throws Exception {
        guardService.ban(null, MAC_B, "TEST");

        mockMvc.perform(withClient(get("/api/health"), "203.0.113.20", MAC_B))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40311));
    }

    @Test
    void verifyThenActivateSucceedsAndNoBlacklist() throws Exception {
        String ip = "203.0.113.30";
        String deviceId = UUID.randomUUID().toString();

        mockMvc.perform(withClient(TestSigner.signedGet("/api/auth/verify", null), ip, MAC_C))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(verifyStateExists(MAC_C, ip)).isTrue();

        byte[] body = activateBody(deviceId);
        mockMvc.perform(withClient(TestSigner.signedJsonPost(post("/api/auth/activate"),
                        "/api/auth/activate", body), ip, MAC_C))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(deviceId));

        assertThat(blacklistContains("client_blacklist_ip", "ip", ip)).isFalse();
        assertThat(blacklistContains("client_blacklist_mac", "mac", MAC_C)).isFalse();
    }

    @Test
    void activateWithoutVerifyBansMacAndIp() throws Exception {
        String ip = "203.0.113.40";
        byte[] body = activateBody(UUID.randomUUID().toString());

        mockMvc.perform(withClient(TestSigner.signedJsonPost(post("/api/auth/activate"),
                        "/api/auth/activate", body), ip, MAC_D))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40313));

        assertThat(blacklistContains("client_blacklist_ip", "ip", ip)).isTrue();
        assertThat(blacklistContains("client_blacklist_mac", "mac", MAC_D)).isTrue();

        // 已被封：之后即使先 verify 也会被黑名单先拦下
        mockMvc.perform(withClient(TestSigner.signedGet("/api/auth/verify", null), ip, MAC_D))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40310));
    }

    @Test
    void manualDeleteUnbansButRemainingRowStillBlocks() throws Exception {
        String ip = "203.0.113.50";
        guardService.ban(ip, MAC_E, "TEST");

        mockMvc.perform(withClient(get("/api/health"), ip, MAC_E))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40310));

        // 只删 IP 行：MAC 行还在 → MAC 命中继续拦截
        deleteBlacklistRow("client_blacklist_ip", "ip", ip);
        mockMvc.perform(withClient(get("/api/health"), ip, MAC_E))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40311));

        // 两张表都删干净 → 立即恢复，无需重启
        deleteBlacklistRow("client_blacklist_mac", "mac", MAC_E);
        mockMvc.perform(withClient(get("/api/health"), ip, MAC_E))
                .andExpect(status().isOk());
    }

    @Test
    void macHeaderIsNormalized() throws Exception {
        // 小写 + 横线分隔的合法 MAC 应被规范化后放行（health 无签名）
        mockMvc.perform(withClient(get("/api/health"), "203.0.113.60", "aa-bb-cc-00-00-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 非法 MAC（含非十六进制字符）按缺失处理 → 封禁 + 40312
        mockMvc.perform(withClient(get("/api/health"), "203.0.113.61", "zz:zz:zz:zz:zz:zz"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40312));
        assertThat(blacklistContains("client_blacklist_ip", "ip", "203.0.113.61")).isTrue();
    }

    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder builder, String ip, String mac) {
        if (mac != null) {
            builder.header(ClientGuardService.HEADER_DEVICE_MAC, mac);
        }
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private static byte[] activateBody(String deviceId) {
        return ("{\"deviceId\":\"" + deviceId + "\",\"deviceName\":\"测试设备\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private boolean blacklistContains(String table, String column, String value) throws Exception {
        Path db = storagePaths.metaDir().resolve("metadata.sqlite3");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM " + table + " WHERE " + column + " = ? LIMIT 1")) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean verifyStateExists(String mac, String ip) throws Exception {
        Path db = storagePaths.metaDir().resolve("metadata.sqlite3");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM client_verify_state WHERE mac = ? AND ip = ?")) {
            statement.setString(1, mac);
            statement.setString(2, ip);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void deleteBlacklistRow(String table, String column, String value) throws Exception {
        Path db = storagePaths.metaDir().resolve("metadata.sqlite3");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE " + column + " = ?")) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }
}
