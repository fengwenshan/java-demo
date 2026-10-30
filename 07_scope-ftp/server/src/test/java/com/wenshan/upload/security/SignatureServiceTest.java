package com.wenshan.upload.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约 2.3 两个签名测试向量（固定 Clock = 1760000000000ms）。
 */
class SignatureServiceTest {

    private static final String APP_KEY = "demo-app";
    private static final String APP_SECRET = "demo-secret-please-change-32-bytes-min";
    private static final String NONCE = "0b9f6c1e-2d1a-4c1b-9a7e-3f1c2b3d4e5f";
    private static final long FIXED_NOW = 1_760_000_000_000L;

    private final SignatureService service =
            new SignatureService(Clock.fixed(Instant.ofEpochMilli(FIXED_NOW), ZoneOffset.UTC));

    @Test
    void vector1_getVerifyWithoutBody() {
        String timestamp = String.valueOf(service.nowMillis());
        assertThat(timestamp).isEqualTo("1760000000000");

        String canonical = service.canonicalString(
                APP_KEY, timestamp, NONCE, "GET", "/api/auth/verify", "", SignatureService.emptyPayloadHash());

        String expectedCanonical = """
                v1
                demo-app
                1760000000000
                0b9f6c1e-2d1a-4c1b-9a7e-3f1c2b3d4e5f
                GET
                /api/auth/verify

                e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855""";
        assertThat(canonical).isEqualTo(expectedCanonical);

        String signature = service.sign(APP_SECRET, canonical);
        assertThat(signature).isEqualTo("9ca81faf26909bec390a1bba9a38a02c812843d9a64443a1e226522ceaa5a14b");
        assertThat(service.verify(APP_SECRET, canonical, signature)).isTrue();
        assertThat(service.verify(APP_SECRET, canonical, "0".repeat(64))).isFalse();
    }

    @Test
    void vector2_uploadWithFileContentHello() {
        String timestamp = String.valueOf(service.nowMillis());
        String payloadHash = SignatureService.sha256Hex("hello");
        assertThat(payloadHash).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");

        String canonical = service.canonicalString(
                APP_KEY, timestamp, NONCE, "POST", "/api/files/upload", "", payloadHash);

        String expectedCanonical = """
                v1
                demo-app
                1760000000000
                0b9f6c1e-2d1a-4c1b-9a7e-3f1c2b3d4e5f
                POST
                /api/files/upload

                2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824""";
        assertThat(canonical).isEqualTo(expectedCanonical);

        String signature = service.sign(APP_SECRET, canonical);
        assertThat(signature).isEqualTo("4ce78a59afffd8264f7277fcebbf74daefab380eb95adae218005e6d79302c0c");
        assertThat(service.verify(APP_SECRET, canonical, signature)).isTrue();
    }

    @Test
    void emptyPayloadHashMatchesContract() {
        assertThat(SignatureService.emptyPayloadHash())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void canonicalStringHasExactly8LinesAndNoTrailingNewline() {
        String canonical = service.canonicalString(
                APP_KEY, "1760000000000", NONCE, "GET", "/api/auth/verify", "", SignatureService.emptyPayloadHash());
        assertThat(canonical.split("\n", -1)).hasSize(8);
        assertThat(canonical).doesNotEndWith("\n");
    }
}
