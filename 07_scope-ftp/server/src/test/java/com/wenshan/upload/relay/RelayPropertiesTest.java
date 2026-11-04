package com.wenshan.upload.relay;

import com.wenshan.upload.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelayPropertiesTest {

    @Test
    void defaultsAreSafeAndConservative() {
        AppProperties properties = new AppProperties();
        AppProperties.Relay relay = properties.getRelay();

        assertThat(relay.isEnabled()).isFalse();
        assertThat(relay.getMaxHops()).isEqualTo(16);
        assertThat(relay.getMaxChainHeaderBytes()).isEqualTo(16 * 1024);
        assertThat(relay.getMaxViaHeaderBytes()).isEqualTo(8 * 1024);
        assertThat(relay.getConnectTimeoutMs()).isEqualTo(3_000);
        assertThat(relay.getRequestTimeoutMs()).isEqualTo(20_000);
        assertThat(relay.getUploadTimeoutMs()).isEqualTo(180_000);
        assertThat(relay.isAllowPrivateNetwork()).isTrue();
        assertThat(relay.isAllowLoopback()).isFalse();
        assertThat(relay.getAllowedHosts()).isEmpty();
    }
}
