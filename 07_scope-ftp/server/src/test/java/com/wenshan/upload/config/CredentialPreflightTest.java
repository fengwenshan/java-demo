package com.wenshan.upload.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialPreflightTest {

    @Test
    void missingBothKeyAndSecretIsReported() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.security.clients[0].app-key", "   ");
        environment.setProperty("app.security.clients[0].app-secret", "   ");
        assertThat(CredentialPreflight.findProblem(environment))
                .isEqualTo("缺少 --app-key 和 --app-secret（app.security.clients[0] 为空）");
    }

    @Test
    void missingSecretIsReportedWhenKeyPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.security.clients[0].app-key", "demo-app");
        assertThat(CredentialPreflight.findProblem(environment))
                .isEqualTo("缺少 --app-secret（app.security.clients[0].app-secret 为空）");
    }

    @Test
    void missingKeyIsReportedWhenSecretPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.security.clients[0].app-secret", "secret");
        assertThat(CredentialPreflight.findProblem(environment))
                .isEqualTo("缺少 --app-key（app.security.clients[0].app-key 为空）");
    }

    @Test
    void bothPresentPassesEvenWhenSecretIsShort() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.security.clients[0].app-key", "demo-app");
        environment.setProperty("app.security.clients[0].app-secret", "short");
        assertThat(CredentialPreflight.findProblem(environment)).isNull();
    }

    @Test
    void noClientsIsReported() {
        MockEnvironment environment = new MockEnvironment();
        assertThat(CredentialPreflight.findProblem(environment))
                .isEqualTo("没有找到启用的客户端凭证（app.security.clients 为空或全部 enabled=false）");
    }

    @Test
    void disabledClientIsSkipped() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.security.clients[0].app-key", "demo-app");
        environment.setProperty("app.security.clients[0].app-secret", "secret");
        environment.setProperty("app.security.clients[0].enabled", "false");
        assertThat(CredentialPreflight.findProblem(environment))
                .isEqualTo("没有找到启用的客户端凭证（app.security.clients 为空或全部 enabled=false）");
    }
}
