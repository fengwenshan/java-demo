package com.wenshan.upload.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayViaCodecTest {

    private final RelayViaCodec codec = new RelayViaCodec(new ObjectMapper());

    @Test
    void blankMeansEmpty() {
        assertThat(codec.decode(null, 8192)).isEmpty();
        assertThat(codec.decode("  ", 8192)).isEmpty();
    }

    @Test
    void appendAndRoundTrip() {
        List<RelayViaHop> via = codec.append(
                List.of(new RelayViaHop("a", "A", "http://10.0.0.1:8080")),
                new RelayViaHop("b", "B", "http://10.0.0.2:8080"),
                8192);
        assertThat(via).extracting(RelayViaHop::name).containsExactly("A", "B");
        assertThat(codec.decode(codec.encode(via), 8192)).isEqualTo(via);
    }

    @Test
    void rejectsTooLarge() {
        List<RelayViaHop> via = List.of(
                new RelayViaHop("a", "A", "http://10.0.0.1:8080"),
                new RelayViaHop("b", "B", "http://10.0.0.2:8080"));
        assertThatThrownBy(() -> codec.encode(via, 8))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }

    @Test
    void appendRejectsWhenOversized() {
        assertThatThrownBy(() -> codec.append(
                List.of(new RelayViaHop("a", "A", "http://10.0.0.1:8080")),
                new RelayViaHop("b", "B", "http://10.0.0.2:8080"), 8))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsMalformedBase64AndJson() {
        assertThatThrownBy(() -> codec.decode("not-base64-$$$", 8192))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RELAY_CHAIN_INVALID);
    }
}
