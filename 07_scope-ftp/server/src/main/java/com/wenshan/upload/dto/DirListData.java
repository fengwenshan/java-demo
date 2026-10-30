package com.wenshan.upload.dto;

import java.util.List;

/**
 * 只读目录浏览响应：{@code path} 为相对存储根目录的当前目录（根目录为空字符串），
 * {@code parent} 为父目录相对路径（根目录时为 null）。
 */
public record DirListData(
        String path,
        String parent,
        boolean truncated,
        List<DirEntry> entries
) {
}
