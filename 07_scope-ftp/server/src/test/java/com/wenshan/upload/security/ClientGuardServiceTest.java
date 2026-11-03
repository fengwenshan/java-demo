package com.wenshan.upload.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAC 规范化单测：只接受 12 位十六进制（允许常见分隔符），输出大写冒号格式。
 */
class ClientGuardServiceTest {

    @Test
    void normalizesCommonFormats() {
        assertThat(ClientGuardService.normalizeMac("aa:bb:cc:dd:ee:ff")).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(ClientGuardService.normalizeMac("AA-BB-CC-DD-EE-FF")).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(ClientGuardService.normalizeMac("aabbccddeeff")).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(ClientGuardService.normalizeMac("  aa.bb.cc.dd.ee.ff  ")).isEqualTo("AA:BB:CC:DD:EE:FF");
    }

    @Test
    void rejectsMalformedMac() {
        assertThat(ClientGuardService.normalizeMac(null)).isNull();
        assertThat(ClientGuardService.normalizeMac("")).isNull();
        assertThat(ClientGuardService.normalizeMac("   ")).isNull();
        assertThat(ClientGuardService.normalizeMac("aa:bb:cc:dd:ee")).isNull();
        assertThat(ClientGuardService.normalizeMac("aa:bb:cc:dd:ee:ff:00")).isNull();
        assertThat(ClientGuardService.normalizeMac("zz:zz:zz:zz:zz:zz")).isNull();
        assertThat(ClientGuardService.normalizeMac("aa:bb:cc:dd:ee:fg")).isNull();
        assertThat(ClientGuardService.normalizeMac("hello-world!!")).isNull();
    }
}
