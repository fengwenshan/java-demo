package com.wenshan.upload.dto;

import java.util.List;

/**
 * 契约 3.2 校验连接响应 data。
 */
public record VerifyData(
        String appKey,
        String name,
        long serverTime,
        boolean deviceBindingEnabled,
        DeviceInfo device,
        Limits limits,
        /** 默认存储根目录绝对路径（= defaultRootId 对应的根，保留旧字段兼容）。 */
        String storageDir,
        /** 全部存储根（多根客户端展示用）。 */
        List<StorageRootInfo> roots,
        /** 默认根 id（接口不传 root 时使用）。 */
        String defaultRootId
) {
}
