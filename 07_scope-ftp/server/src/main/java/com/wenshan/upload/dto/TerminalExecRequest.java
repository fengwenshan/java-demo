package com.wenshan.upload.dto;

/**
 * 契约 3.21 远程终端执行请求（JSON）。
 *
 * @param sessionId 客户端生成的会话 id：8-64 位 {@code [A-Za-z0-9_-]}，服务端按会话维护 cwd
 * @param command   单行命令（1-4096 字符，不允许换行/NUL）
 * @param cwd       可选：首次连接可指定绝对目录（必须存在且位于 app.storage.roots 内；
 *                  后续以服务端会话 cwd 为准）
 * @param root      可选：存储根 id，首次会话未指定 cwd 时用该根作为 cwd（合法 id），
 *                  非法/为空回退默认第一个根
 */
public record TerminalExecRequest(String sessionId, String command, String cwd, String root) {

    /** 兼容旧调用方（未传 root）。 */
    public TerminalExecRequest(String sessionId, String command, String cwd) {
        this(sessionId, command, cwd, null);
    }
}
