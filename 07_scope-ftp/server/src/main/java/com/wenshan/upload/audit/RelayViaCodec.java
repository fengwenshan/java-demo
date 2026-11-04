package com.wenshan.upload.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * {@code X-Relay-Via} 的 Base64URL(无 padding) JSON 编解码：每个 relay 节点在转发前追加自己。
 *
 * <p>空/缺省表示没有来源（root）；解析失败或超长由调用方决定是拒绝（relay 节点）还是记空（最终目标审计）。</p>
 */
@Component
public class RelayViaCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;

    public RelayViaCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RelayViaHop> decode(String header, int maxBytes) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        if (header.length() > maxBytes) {
            throw invalid("来源链过长");
        }
        try {
            byte[] bytes = DECODER.decode(header);
            if (bytes.length > maxBytes) {
                throw invalid("来源链过长");
            }
            List<RelayViaHop> hops = objectMapper.readValue(bytes, new TypeReference<List<RelayViaHop>>() {
            });
            return hops == null ? List.of() : List.copyOf(hops);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("来源链无效");
        }
    }

    public List<RelayViaHop> append(List<RelayViaHop> existing, RelayViaHop hop, int maxBytes) {
        List<RelayViaHop> next = new ArrayList<>(existing == null ? List.of() : existing);
        next.add(hop);
        encode(next, maxBytes);
        return List.copyOf(next);
    }

    public String encode(List<RelayViaHop> hops) {
        return encode(hops, Integer.MAX_VALUE);
    }

    public String encode(List<RelayViaHop> hops, int maxBytes) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(hops);
            String header = ENCODER.encodeToString(bytes);
            if (header.length() > maxBytes) {
                throw invalid("来源链过长");
            }
            return header;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("来源链编码失败");
        }
    }

    private BizException invalid(String message) {
        return new BizException(ErrorCode.RELAY_CHAIN_INVALID, message);
    }
}
