package com.wenshan.upload.dto;

/**
 * 契约 3.21 远程终端执行结果。
 *
 * <p>{@code cwd} 为执行后的会话工作目录；{@code exitCode} 为命令退出码（超时为 -1）；
 * {@code truncated} 表示输出超过 max-output-bytes 被截断；命令非零退出仍返回 code=0。</p>
 */
public record TerminalExecData(
        String sessionId,
        String command,
        String output,
        int exitCode,
        String cwd,
        boolean truncated,
        long durationMs
) {
}
