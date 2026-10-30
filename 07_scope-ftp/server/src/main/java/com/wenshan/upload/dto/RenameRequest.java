package com.wenshan.upload.dto;

/**
 * 契约 3.17 {@code POST /api/files/rename} 请求体（JSON）。
 *
 * <p>{@code root} 为存储根 id（可选，缺省=默认根）；{@code path} 为目标项相对该根的原路径；
 * {@code newName} 为新的单段名字（不改变所在目录）。</p>
 */
public record RenameRequest(
        String root,
        String path,
        String newName
) {
}
