package com.wenshan.upload.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionTest {

    @Test
    void resolvesRelayWrappedPathWithRelayPrefix() {
        assertThat(AuditAction.resolve("GET", "/api/relay/api/files"))
                .isEqualTo("relay.files.list");
        assertThat(AuditAction.resolve("POST", "/api/relay/api/files/upload/init"))
                .isEqualTo("relay.upload.init");
        assertThat(AuditAction.resolve("GET", "/api/relay/api/audit/logs"))
                .isEqualTo("relay.audit.list");
        assertThat(AuditAction.resolve("PUT", "/api/relay/api/files/upload/up-1/chunk"))
                .isEqualTo("relay.upload.chunk");
    }

    @Test
    void keepsNormalPathAction() {
        assertThat(AuditAction.resolve("GET", "/api/files")).isEqualTo("files.list");
        assertThat(AuditAction.resolve("POST", "/api/files/upload/init")).isEqualTo("upload.init");
        assertThat(AuditAction.resolve("POST", "/api/files/create")).isEqualTo("create");
    }

    @Test
    void fallsBackForUnknownRelayPath() {
        assertThat(AuditAction.resolve("GET", "/api/relay/api/unknown"))
                .isEqualTo("relay.GET /api/unknown");
    }
}
