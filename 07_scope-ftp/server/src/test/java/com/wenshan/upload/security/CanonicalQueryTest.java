package com.wenshan.upload.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约 2.4 查询串规范化。
 */
class CanonicalQueryTest {

    @Test
    void emptyQueryString() {
        assertThat(CanonicalQuery.canonicalize(null)).isEmpty();
        assertThat(CanonicalQuery.canonicalize("")).isEmpty();
    }

    @Test
    void contractExample() {
        assertThat(CanonicalQuery.canonicalize("size=20&page=1&keyword=中文 空格"))
                .isEqualTo("keyword=%E4%B8%AD%E6%96%87%20%E7%A9%BA%E6%A0%BC&page=1&size=20");
    }

    @Test
    void sortsByKeyThenValue() {
        assertThat(CanonicalQuery.canonicalize("b=2&a=2&a=1&c="))
                .isEqualTo("a=1&a=2&b=2&c=");
    }

    @Test
    void plusIsNotSpaceAndReservedCharsAreReencoded() {
        assertThat(CanonicalQuery.canonicalize("q=a+b&path=%2Ftmp%2Fx&tilde=~-._"))
                .isEqualTo("path=%2Ftmp%2Fx&q=a%2Bb&tilde=~-._");
    }

    @Test
    void percentDecodeThenEncodeIsIdempotentForCanonicalForm() {
        String raw = "keyword=%E4%B8%AD%E6%96%87&page=1&size=20";
        String canonical = CanonicalQuery.canonicalize(raw);
        assertThat(canonical).isEqualTo("keyword=%E4%B8%AD%E6%96%87&page=1&size=20");
        assertThat(CanonicalQuery.canonicalize(canonical)).isEqualTo(canonical);
    }

    @Test
    void onlyFirstEqualsSplitsPair() {
        assertThat(CanonicalQuery.canonicalize("token=a=b"))
                .isEqualTo("token=a%3Db");
    }
}
