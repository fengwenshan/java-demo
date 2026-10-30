package com.wenshan.upload.dto;

/**
 * 存储根目录信息（供客户端展示/用户确认）：{@code path} 为服务端解析后的绝对路径。
 */
public record StorageRootInfo(
        String id,
        String name,
        String path
) {
}
