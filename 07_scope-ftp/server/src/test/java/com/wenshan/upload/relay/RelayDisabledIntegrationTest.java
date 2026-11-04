package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.support.RelayTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * relay 关闭时 /api/relay 返回 40320，且完全不发起下游请求。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.relay.enabled=false")
@ActiveProfiles("test")
class RelayDisabledIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    @Test
    void returnsRelayDisabled() throws Exception {
        RelayTestClient client = new RelayTestClient(port);
        HttpResponse<byte[]> response = client.send("GET", "/api/relay/api/files", null,
                null, null, null,
                List.of(RelayTestClient.hop("http://127.0.0.1:1", "B",
                        RelayTestClient.B_APP_KEY, RelayTestClient.B_DEVICE_ID)));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(MAPPER.readTree(response.body()).path("code").asInt()).isEqualTo(40320);
    }
}
