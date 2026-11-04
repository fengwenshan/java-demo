package com.wenshan.upload.relay;

import com.wenshan.upload.common.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelayErrorMapperTest {

    private final RelayErrorMapper mapper = new RelayErrorMapper();

    @Test
    void mapsAuthAndPermissionFailures() {
        assertThat(mapper.map(401, null)).isEqualTo(ErrorCode.RELAY_TARGET_UNAUTHORIZED);
        assertThat(mapper.map(403, null)).isEqualTo(ErrorCode.RELAY_TARGET_IDENTITY_REJECTED);
    }

    @Test
    void mapsRateLimitRedirectAndServerErrors() {
        assertThat(mapper.map(302, null)).isEqualTo(ErrorCode.RELAY_TARGET_BAD_RESPONSE);
        assertThat(mapper.map(429, null)).isEqualTo(ErrorCode.RELAY_TARGET_BAD_RESPONSE);
        assertThat(mapper.map(500, null)).isEqualTo(ErrorCode.RELAY_TARGET_BAD_RESPONSE);
        assertThat(mapper.map(502, null)).isEqualTo(ErrorCode.RELAY_TARGET_BAD_RESPONSE);
    }

    @Test
    void leavesBusiness4xxToPassThrough() {
        assertThat(mapper.map(400, 40000)).isNull();
        assertThat(mapper.map(404, 40400)).isNull();
        assertThat(mapper.map(413, 40003)).isNull();
    }

    @Test
    void keepsDeviceBindingAndRateLimitAndClockErrorsForClient() {
        // 40300 设备绑定：下游语义明确，透传让客户端提示「设备未授权」。
        assertThat(mapper.map(403, 40300)).isNull();
        // 42900 限流：透传下游 code。
        assertThat(mapper.map(429, 42900)).isNull();
        // 40101 时间偏差：透传下游 message 便于定位是哪一跳。
        assertThat(mapper.map(401, 40101)).isNull();
    }

    @Test
    void mapsIdentityRejections() {
        for (int code : new int[]{40310, 40311, 40312, 40313}) {
            assertThat(mapper.map(403, code)).isEqualTo(ErrorCode.RELAY_TARGET_IDENTITY_REJECTED);
        }
    }
}
