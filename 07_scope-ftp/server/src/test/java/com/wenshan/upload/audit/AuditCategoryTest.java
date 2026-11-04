package com.wenshan.upload.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditCategoryTest {

    @Test
    void operationCategoryIncludesRelayPrefixedActions() {
        assertThat(AuditCategory.actions(AuditCategory.OPERATION))
                .contains("upload", "relay.upload", "terminal.exec", "relay.terminal.exec");
    }

    @Test
    void connectionCategoryIncludesRelayPrefixedActions() {
        assertThat(AuditCategory.actions(AuditCategory.CONNECTION))
                .contains("auth.verify", "relay.auth.verify", "auth.activate", "relay.auth.activate");
    }

    @Test
    void combinedCategoryIncludesRelayOperationsAndConnections() {
        assertThat(AuditCategory.actions(AuditCategory.BOTH))
                .contains("relay.download", "relay.upload.complete", "relay.auth.verify");
    }
}
