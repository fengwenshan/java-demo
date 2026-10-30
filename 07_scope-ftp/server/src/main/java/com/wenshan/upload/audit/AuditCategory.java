package com.wenshan.upload.audit;

import java.util.List;
import java.util.Locale;

/**
 * 日志分类：区分「对服务器的实际操作」和「连接/激活」。
 *
 * <p>操作日志不再混入目录列表、搜索、存储根查询、审计查询、分片上传状态等
 * 接口请求噪声；这些请求仍可作为安全审计数据保留，只是不进入合并日志视图。</p>
 */
public final class AuditCategory {

    public static final String OPERATION = "operation";
    public static final String CONNECTION = "connection";
    public static final String BOTH = "both";

    /** 会在界面上体现为一次服务器操作的动作。 */
    private static final List<String> OPERATION_ACTIONS = List.of(
            "upload",
            "upload.complete",
            "download",
            "download.byId",
            "delete",
            "rename",
            "copy",
            "move",
            "extract",
            "content.read",
            "content.save",
            "terminal.exec");

    /** 连接验证 / 设备激活。 */
    private static final List<String> CONNECTION_ACTIONS = List.of(
            "auth.verify",
            "auth.activate");

    /** 客户端日志抽屉的合并视图：文件/终端操作 + 连接验证/设备激活。 */
    private static final List<String> COMBINED_ACTIONS = List.of(
            "upload",
            "upload.complete",
            "download",
            "download.byId",
            "delete",
            "rename",
            "copy",
            "move",
            "extract",
            "content.read",
            "content.save",
            "terminal.exec",
            "auth.verify",
            "auth.activate");

    private AuditCategory() {
    }

    /**
     * 返回分类对应的 action 列表；未知分类返回 {@code null}，空分类也返回 {@code null}。
     */
    public static List<String> actions(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return switch (category.trim().toLowerCase(Locale.ROOT)) {
            case OPERATION -> OPERATION_ACTIONS;
            case CONNECTION -> CONNECTION_ACTIONS;
            case BOTH -> COMBINED_ACTIONS;
            default -> null;
        };
    }

    public static boolean isKnown(String category) {
        return category == null || category.isBlank() || actions(category) != null;
    }
}
