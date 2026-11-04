package com.wenshan.upload.relay;

import com.wenshan.upload.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 下游错误到本跳统一 relay 错误码的映射。
 *
 * <p>返回 {@code null} 表示「透传下游状态码/code/body」：业务 4xx、设备绑定 40300、
 * 限流 42900、时钟 40101。其余 401/403/3xx/429/5xx 改写成 relay 错误码；
 * 下游错误体不是 JSON 时调用方按 {@code null} 视为非法响应，走 50211。</p>
 */
@Component
public class RelayErrorMapper {

    public ErrorCode map(int status, Integer downstreamCode) {
        if (status == 401) {
            // 40101 请求过期：保留下游 code/message，客户端据此提示是哪一跳时钟偏差。
            if (downstreamCode != null && downstreamCode == ErrorCode.REQUEST_EXPIRED.getCode()) {
                return null;
            }
            return ErrorCode.RELAY_TARGET_UNAUTHORIZED;
        }
        if (status == 403) {
            // 40300 设备绑定是下游明确的业务语义，透传给客户端。
            if (downstreamCode != null && downstreamCode == ErrorCode.DEVICE_UNAUTHORIZED.getCode()) {
                return null;
            }
            return ErrorCode.RELAY_TARGET_IDENTITY_REJECTED;
        }
        if (status == 429) {
            // 下游限流：透传 42900，让客户端按限流提示处理。
            if (downstreamCode != null && downstreamCode == ErrorCode.RATE_LIMITED.getCode()) {
                return null;
            }
            return ErrorCode.RELAY_TARGET_BAD_RESPONSE;
        }
        if (status >= 300 && status < 400) {
            return ErrorCode.RELAY_TARGET_BAD_RESPONSE;
        }
        if (status >= 500) {
            // 下游 JSON 业务错误体：透传 status/code；非 JSON 才视为非法响应 50211。
            if (downstreamCode != null) {
                return null;
            }
            return ErrorCode.RELAY_TARGET_BAD_RESPONSE;
        }
        return null;
    }
}
