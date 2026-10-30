package com.wenshan.upload.dto;

/**
 * 契约 3.17 重命名响应 data：相对 root 的原/新路径，以及被重命名项类型（file/directory）。
 */
public record RenameData(String oldPath, String newPath, String type) {
}
