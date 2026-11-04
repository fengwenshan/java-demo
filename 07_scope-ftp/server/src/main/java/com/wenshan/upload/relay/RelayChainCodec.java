package com.wenshan.upload.relay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code X-Relay-Chain} 的 Base64URL(无 padding) JSON 编解码与校验。
 *
 * <p>链项按 root → target 排序；同一链中 appKey 不得重复（避免验签/来源歧义），
 * 长度、跳数、字段长度都受配置上限约束。任何非法输入统一映射 40010。</p>
 */
@Component
public class RelayChainCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;

    public RelayChainCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RelayHop> decode(String header, int maxHops, int maxBytes) {
        if (header == null || header.isBlank()) {
            throw invalid("缺少转发链");
        }
        if (header.length() > maxBytes) {
            throw invalid("转发链过长");
        }
        byte[] bytes;
        try {
            bytes = DECODER.decode(header);
        } catch (IllegalArgumentException e) {
            throw invalid("转发链不是合法的 Base64URL");
        }
        if (bytes.length > maxBytes) {
            throw invalid("转发链过长");
        }
        List<RelayHop> hops;
        try {
            hops = objectMapper.readValue(bytes, new TypeReference<List<RelayHop>>() {
            });
        } catch (Exception e) {
            throw invalid("转发链 JSON 无效");
        }
        if (hops == null || hops.isEmpty()) {
            throw invalid("转发链为空");
        }
        if (hops.size() > maxHops) {
            throw invalid("转发链超过最大跳数");
        }
        Set<String> appKeys = new HashSet<>();
        for (RelayHop hop : hops) {
            validate(hop);
            if (!appKeys.add(hop.appKey().toLowerCase(Locale.ROOT))) {
                throw invalid("转发链 appKey 重复");
            }
        }
        return List.copyOf(hops);
    }

    public String encode(List<RelayHop> hops) {
        if (hops == null || hops.isEmpty()) {
            throw invalid("转发链为空");
        }
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(hops);
            return ENCODER.encodeToString(bytes);
        } catch (Exception e) {
            throw new BizException(ErrorCode.RELAY_CHAIN_INVALID, "转发链编码失败");
        }
    }

    private void validate(RelayHop hop) {
        if (hop == null) {
            throw invalid("转发链包含空跳");
        }
        require(hop.url(), 2048, "url");
        require(hop.name(), 64, "name");
        require(hop.appKey(), 128, "appKey");
        require(hop.nonce(), 128, "nonce");
        require(hop.signature(), 256, "signature");
        require(hop.deviceId(), 128, "deviceId");
        if (hop.timestamp() <= 0) {
            throw invalid("转发链 timestamp 无效");
        }
    }

    private void require(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw invalid("转发链 " + field + " 无效");
        }
    }

    private BizException invalid(String message) {
        return new BizException(ErrorCode.RELAY_CHAIN_INVALID, message);
    }
}
