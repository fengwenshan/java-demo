package com.wenshan.upload.storage;

import com.wenshan.upload.dto.StorageRootInfo;
import com.wenshan.upload.store.MetaStore;

import java.nio.file.Path;

/**
 * 一个已生效的存储根：配置信息 + 该根专属的严格路径校验器 + 该根专属的元数据存储。
 *
 * <p>关键隔离点：{@link #paths()} 只认识本根的绝对路径，所有文件操作都通过它解析，
 * 因此「请求指定 root=A」时路径不可能被解析到 B 或 root 之外。</p>
 */
public record StorageRoot(
        String id,
        String name,
        Path path,
        StoragePaths paths,
        MetaStore metaStore
) {

    /** 对外展示信息（path 为绝对路径）。 */
    public StorageRootInfo info() {
        return new StorageRootInfo(id, name, path.toString());
    }
}
