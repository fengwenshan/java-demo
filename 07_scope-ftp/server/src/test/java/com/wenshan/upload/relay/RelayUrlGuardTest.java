package com.wenshan.upload.relay;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayUrlGuardTest {

    private final AppProperties properties = new AppProperties();
    private final RelayUrlGuard guard = new RelayUrlGuard(properties);

    @Test
    void acceptsPrivateHttpUrl() {
        assertThat(guard.validate("http://10.0.0.5:8080/scopeftp").toString())
                .isEqualTo("http://10.0.0.5:8080/scopeftp");
    }

    @Test
    void acceptsHttpsUrl() {
        assertThat(guard.validate("https://10.0.0.5:8443/scopeftp").toString())
                .isEqualTo("https://10.0.0.5:8443/scopeftp");
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
        assertThatThrownBy(() -> guard.validate("ftp://10.0.0.5/"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsUserInfoAndQueryAndFragment() {
        assertThatThrownBy(() -> guard.validate("http://user:pass@10.0.0.5:8080"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> guard.validate("http://10.0.0.5:8080?a=1"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> guard.validate("http://10.0.0.5:8080#x"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsLoopbackByDefault() {
        assertThatThrownBy(() -> guard.validate("http://127.0.0.1:8080"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
        assertThatThrownBy(() -> guard.validate("http://[::1]:8080"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void allowsLoopbackOnlyWhenExplicitlyEnabled() {
        properties.getRelay().setAllowLoopback(true);
        assertThat(guard.validate("http://127.0.0.1:8080")).hasToString("http://127.0.0.1:8080");
    }

    @Test
    void rejectsLinkLocalAndMetadataAddresses() {
        assertThatThrownBy(() -> guard.validate("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsPrivateNetworkWhenDisabled() {
        properties.getRelay().setAllowPrivateNetwork(false);
        assertThatThrownBy(() -> guard.validate("http://10.0.0.5:8080"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
        assertThatThrownBy(() -> guard.validate("http://192.168.1.2:8080"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void allowedHostsIsAnAllowlistWhenConfigured() {
        properties.getRelay().setAllowedHosts(java.util.List.of("10.0.0.5"));
        assertThat(guard.validate("http://10.0.0.5:8080")).hasToString("http://10.0.0.5:8080");
        assertThatThrownBy(() -> guard.validate("http://10.0.0.6:8080"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void allowedHostsSupportsHostAndPortForm() {
        properties.getRelay().setAllowedHosts(java.util.List.of("10.0.0.5:8080"));
        assertThat(guard.validate("http://10.0.0.5:8080")).hasToString("http://10.0.0.5:8080");
        assertThatThrownBy(() -> guard.validate("http://10.0.0.5:9090"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsMissingHostOrBlankAddress() {
        assertThatThrownBy(() -> guard.validate(" "))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
        assertThatThrownBy(() -> guard.validate("http:///path"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }
}
