package com.wenshan.upload.dto;

/**
 * {@code POST /api/files/create} 请求体（JSON）：在指定存储根的目录下新建空文件或文件夹。
 *
 * <p>{@code root} 为存储根 id（可选，缺省=默认根）；{@code parent} 为父目录相对该根的路径
 * （空/缺省=根目录）；{@code name} 为单段名称；{@code type} 只能是 {@code file} 或
 * {@code directory}。</p>
 */
public record CreateEntryRequest(
        String root,
        String parent,
        String name,
        String type
) {
}
