package com.wenshan.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UploadApplicationTest {

    @Test
    void shortCredentialFlagsMapToFirstClientProperties() {
        String[] normalized = UploadApplication.normalizeCredentialArgs(new String[] {
                "--server.port=18080",
                "--app-key", "my-key",
                "--app-secret=my-secret",
                "--spring.profiles.active=default"
        });

        assertThat(normalized).containsExactly(
                "--server.port=18080",
                "--app.security.clients[0].app-key=my-key",
                "--app.security.clients[0].app-secret=my-secret",
                "--spring.profiles.active=default");
    }

    @Test
    void missingCredentialValueFailsFast() {
        assertThatThrownBy(() -> UploadApplication.normalizeCredentialArgs(new String[] {"--app-key"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--app-key 缺少值");
    }

    @Test
    void emptyArgsStayEmpty() {
        assertThat(UploadApplication.normalizeCredentialArgs(null)).isEmpty();
        assertThat(UploadApplication.normalizeCredentialArgs(new String[0])).isEmpty();
    }
}
