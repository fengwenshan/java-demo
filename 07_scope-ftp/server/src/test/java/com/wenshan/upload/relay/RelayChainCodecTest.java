package com.wenshan.upload.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayChainCodecTest {

    private final RelayChainCodec codec = new RelayChainCodec(new ObjectMapper());

    private RelayHop hop(String name) {
        return new RelayHop(
                "http://10.0.0." + name.charAt(0) + ":8080",
                name,
                "app-" + name,
                System.currentTimeMillis(),
                "nonce-" + name,
                "sig-" + name,
                "device-" + name);
    }

    @Test
    void roundTripsTwoHops() {
        List<RelayHop> hops = List.of(hop("A"), hop("B"));
        String header = codec.encode(hops);

        assertThat(header).doesNotContain("=");
        assertThat(codec.decode(header, 16, 16 * 1024)).isEqualTo(hops);
    }

    @Test
    void rejectsMalformedBase64() {
        assertThatThrownBy(() -> codec.decode("not-base64-$$$", 16, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsEmptyChain() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("[]".getBytes());
        assertThatThrownBy(() -> codec.decode(header, 16, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsTooManyHops() {
        String header = codec.encode(List.of(hop("A"), hop("B"), hop("C")));
        assertThatThrownBy(() -> codec.decode(header, 2, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsMissingField() {
        String json = "[{\"url\":\"http://10.0.0.5:8080\",\"name\":\"A\"}]";
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes());
        assertThatThrownBy(() -> codec.decode(header, 16, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsOversizedHeader() {
        String header = codec.encode(List.of(hop("A"), hop("B")));
        assertThatThrownBy(() -> codec.decode(header, 16, 8))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void rejectsInvalidTimestampAndBlankFields() {
        String json = "[{\"url\":\"http://10.0.0.5:8080\",\"name\":\"A\",\"appKey\":\"a\","
                + "\"timestamp\":0,\"nonce\":\"n\",\"signature\":\"s\",\"deviceId\":\"d\"}]";
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes());
        assertThatThrownBy(() -> codec.decode(header, 16, 16 * 1024))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }
}
