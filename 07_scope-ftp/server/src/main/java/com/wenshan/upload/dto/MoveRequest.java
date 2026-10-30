package com.wenshan.upload.dto;

/**
 * 契约 3.23 移动请求（JSON）：{@code targetRoot} 省略/空=与源同根；{@code targetDir} 为空串=目标根根目录。
 */
public record MoveRequest(String root, String path, String targetRoot, String targetDir) {
}
