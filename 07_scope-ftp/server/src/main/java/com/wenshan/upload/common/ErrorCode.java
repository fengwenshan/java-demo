package com.wenshan.upload.common;

import java.util.List;

/**
 * 契约第 4 节错误码表。
 */
public enum ErrorCode {

    OK(0, 200, "ok"),
    BAD_REQUEST(40000, 400, "请求参数错误"),
    CONTENT_HASH_MISMATCH(40001, 400, "文件内容校验失败"),
    FILE_TOO_LARGE(40003, 413, "文件超过大小限制"),
    INVALID_SIGNATURE(40100, 401, "签名无效"),
    REQUEST_EXPIRED(40101, 401, "请求已过期"),
    REQUEST_REPLAYED(40102, 401, "请求重复"),
    CLIENT_UNAUTHORIZED(40103, 401, "客户端未授权"),
    DEVICE_UNAUTHORIZED(40300, 403, "设备未授权"),
    CLIENT_IP_BLACKLISTED(40310, 403, "IP 已被封禁"),
    CLIENT_MAC_BLACKLISTED(40311, 403, "设备物理地址已被封禁"),
    CLIENT_IDENTITY_MISSING(40312, 403, "缺少设备标识"),
    CALL_ORDER_VIOLATION(40313, 403, "接口调用顺序错误"),
    NOT_FOUND(40400, 404, "资源不存在"),
    RELAY_CHAIN_INVALID(40010, 400, "转发链无效"),
    RELAY_DISABLED(40320, 403, "转发功能未开启"),
    RELAY_TARGET_UNAUTHORIZED(40110, 401, "转发目标鉴权失败"),
    RELAY_TARGET_UNREACHABLE(50210, 502, "转发目标不可达"),
    RELAY_TARGET_BAD_RESPONSE(50211, 502, "转发目标返回异常"),
    RELAY_TARGET_IDENTITY_REJECTED(50212, 502, "转发目标拒绝来源身份"),
    RELAY_TIMEOUT(50410, 504, "转发目标超时"),
    RATE_LIMITED(42900, 429, "请求过于频繁"),
    INTERNAL_ERROR(50000, 500, "服务器内部错误");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }

    public static List<ErrorCode> all() {
        return List.of(values());
    }
}
