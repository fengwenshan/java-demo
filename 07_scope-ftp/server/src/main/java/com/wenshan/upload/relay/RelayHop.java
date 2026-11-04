package com.wenshan.upload.relay;

/**
 * 链中的一跳。字段名与客户端 JSON 一致，不能改名。
 *
 * <p>链里只有签名，没有 AppSecret；每一跳的 secret 只保存在客户端。
 * 字段顺序与客户端序列化一致：url / name / appKey / timestamp / nonce / signature / deviceId。</p>
 */
public record RelayHop(
        String url,
        String name,
        String appKey,
        long timestamp,
        String nonce,
        String signature,
        String deviceId) {
}
