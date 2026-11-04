package com.wenshan.upload.audit;

/**
 * 审计里的一条来源。appKey 用于客户端映射到本地服务器，url 作为兜底匹配。
 * 字段名与客户端 JSON 一致，不能改名。
 */
public record RelayViaHop(String appKey, String name, String url) {
}
