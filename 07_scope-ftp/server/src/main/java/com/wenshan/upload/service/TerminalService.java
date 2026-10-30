package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.TerminalExecData;
import com.wenshan.upload.terminal.TerminalGuard;
import com.wenshan.upload.terminal.TerminalPathGuard;
import com.wenshan.upload.terminal.TerminalScope;
import com.wenshan.upload.terminal.TerminalSessionStore;
import com.wenshan.upload.terminal.TerminalSessionStore.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 契约 3.21 远程终端：校验 / 高危拦截 / 安全目录限制 / 会话 cwd / {@code cd} 特殊处理 / 执行与超时截断。
 *
 * <p>安全边界：接口必须经过签名过滤器；命令以服务进程用户身份执行，且 cwd 与命令中的路径参数
 * 都限制在 {@code app.storage.roots} 内。但这是<b>护栏不是沙箱</b>：命令本身（python/git 等）
 * 仍可能间接访问系统文件；{@code app.terminal.enabled=false} 可整体关闭。</p>
 */
@Service
public class TerminalService {

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final String TIMEOUT_SUFFIX = "\n命令执行超时（%d 秒），已终止进程";

    private final AppProperties properties;
    private final TerminalSessionStore sessionStore;
    private final TerminalScope scope;

    public TerminalService(AppProperties properties, TerminalSessionStore sessionStore, TerminalScope scope) {
        this.properties = properties;
        this.sessionStore = sessionStore;
        this.scope = scope;
    }

    /**
     * 执行一条命令；会话按 appKey 隔离（不同客户端即使 sessionId 相同也不会共享 cwd）。
     *
     * @param root 可选存储根 id：首次会话 cwd 用该根；非法/为空回退默认第一个根
     */
    public TerminalExecData exec(String appKey, String sessionId, String command,
                                 String requestedCwd, String root) {
        if (!properties.getTerminal().isEnabled()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "终端功能未启用（app.terminal.enabled=false）");
        }
        String validSessionId = validateSessionId(sessionId);
        String validCommand = validateCommand(command);

        // 1. 先高危命令护栏（不启动进程）
        String guardedReason = TerminalGuard.forbiddenReason(validCommand).orElse(null);
        if (guardedReason != null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "高危命令已禁止执行：" + validCommand + "（原因：" + guardedReason + "）");
        }

        Session session = sessionStore.getOrCreate(sessionKey(appKey, validSessionId),
                requestedCwd, root);
        // 2. 防御历史会话/根变更：执行前 session cwd 必须仍在允许根内
        if (!Files.isDirectory(session.cwd()) || !scope.isInsideAllowed(session.cwd())) {
            throw TerminalScope.outside("目录", session.cwd().toString());
        }

        long start = System.nanoTime();
        // 3. cd 是特殊处理（不启动进程），其目录校验使用「目录不在安全范围」消息
        Path cdTarget = handleCd(session, validCommand);
        if (cdTarget != null) {
            return new TerminalExecData(validSessionId, validCommand, "", 0,
                    session.cwd().toString(), false, elapsedMillis(start));
        }

        // 4. 非 cd 命令：路径参数/重定向目标必须都在允许根内（不启动进程）
        Optional<String> unsafeToken = new TerminalPathGuard(scope.allowedRoots(), session.cwd())
                .violation(validCommand);
        if (unsafeToken.isPresent()) {
            throw new BizException(ErrorCode.BAD_REQUEST, TerminalPathGuard.message(unsafeToken.get()));
        }
        return execute(session, validSessionId, validCommand, start);
    }

    public int sessionCount() {
        return sessionStore.sessionCount();
    }

    private TerminalExecData execute(Session session, String sessionId, String command, long start) {
        int timeoutSeconds = clamp(properties.getTerminal().getTimeoutSeconds(),
                MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        int maxOutputBytes = Math.max(1, properties.getTerminal().getMaxOutputBytes());
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-lc", command)
                .directory(session.cwd().toFile())
                .redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "终端命令启动失败: " + e.getMessage());
        }
        CapturedOutput output = new CapturedOutput(maxOutputBytes);
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                output.drain(input);
            } catch (IOException ignored) {
                // 进程被终止/管道关闭属正常情况
            }
        }, "terminal-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            killProcessTree(process);
            log.info("终端命令超时被终止: session={}, timeout={}s", sessionId, timeoutSeconds);
        }
        try {
            reader.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long durationMs = elapsedMillis(start);
        if (!finished) {
            String text = output.text();
            if (!text.isEmpty() && !text.endsWith("\n")) {
                text = text + "\n";
            }
            text = text + String.format(TIMEOUT_SUFFIX, timeoutSeconds);
            return new TerminalExecData(sessionId, command, text, -1,
                    session.cwd().toString(), output.truncated(), durationMs);
        }
        int exitCode = process.exitValue();
        return new TerminalExecData(sessionId, command, output.text(), exitCode,
                session.cwd().toString(), output.truncated(), durationMs);
    }

    /**
     * {@code cd}/{@code cd <path>}/{@code cd -} 不启动进程，直接更新会话 cwd（必须在允许根内）。
     *
     * @return 处理后的 cwd；不是简单 cd（或带管道/重定向等复杂命令）时返回 null → 按普通命令执行
     */
    private Path handleCd(Session session, String command) {
        String trimmed = command.trim();
        if (!trimmed.equals("cd") && !trimmed.matches("^cd\\s+.*")) {
            return null;
        }
        String argument = trimmed.length() > 2 ? trimmed.substring(2).trim() : "";
        Path target;
        if (argument.isEmpty()) {
            // 无参数 cd：回到当前会话所在根顶部（默认第一根环境即默认根）
            target = scope.rootContaining(session.cwd());
        } else if (argument.equals("-")) {
            if (session.previousCwd() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "没有可返回的上一个目录");
            }
            target = scope.requireDirectoryInside(session.previousCwd(),
                    session.previousCwd().toString());
        } else {
            argument = stripQuotes(argument);
            if (containsShellMeta(argument)) {
                return null;
            }
            Optional<Path> resolved = resolveCdPath(session, argument);
            if (resolved.isEmpty()) {
                return null;
            }
            target = scope.requireDirectoryInside(resolved.get(), argument);
        }
        Path real = TerminalScope.realOrNormalized(target);
        if (!real.equals(session.cwd())) {
            session.changeCwd(real);
        }
        return session.cwd();
    }

    /**
     * 解析 cd 参数为绝对路径（不校验存在/范围，交由 {@link TerminalScope#requireDirectoryInside}）。
     *
     * @return 含无法静态展开的变量时返回 empty（交给 shell 普通执行，由路径防护兜底）
     */
    private Optional<Path> resolveCdPath(Session session, String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        try {
            if (argument.equals("~") || argument.startsWith("~/")) {
                Path home = Path.of(System.getProperty("user.home", "/"));
                return Optional.of(argument.length() > 1 ? home.resolve(argument.substring(2)) : home);
            }
            if (lower.equals("$home") || lower.startsWith("$home/")) {
                Path home = Path.of(System.getProperty("user.home", "/"));
                String rest = argument.length() > 5 ? argument.substring(6) : "";
                return Optional.of(home.resolve(rest));
            }
            if (argument.contains("$")) {
                return Optional.empty();
            }
            Path path = Path.of(argument);
            return Optional.of(path.isAbsolute() ? path : session.cwd().resolve(path));
        } catch (InvalidPathException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不是目录: " + argument);
        }
    }

    private static String sessionKey(String appKey, String sessionId) {
        return appKey + "\u0000" + sessionId;
    }

    private static String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 sessionId");
        }
        String value = sessionId.trim();
        if (!SESSION_ID_PATTERN.matcher(value).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "sessionId 格式非法（8-64 位字母、数字、下划线或短横线）");
        }
        return value;
    }

    private static String validateCommand(String command) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 command");
        }
        String value = command.trim();
        if (value.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "命令不能为空");
        }
        if (value.length() > TerminalGuard.MAX_COMMAND_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "命令过长（最多 " + TerminalGuard.MAX_COMMAND_LENGTH + " 字符）");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0') {
                throw new BizException(ErrorCode.BAD_REQUEST, "命令必须是单行（不允许换行或 NUL）");
            }
        }
        return value;
    }

    private static boolean containsShellMeta(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '|' || c == '&' || c == ';' || c == '<' || c == '>' || c == '`') {
                return true;
            }
        }
        return false;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void killProcessTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // 进程可能已退出
            }
        });
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 合并 stdout/stderr 的限量缓冲：最多保留 maxOutputBytes，超出继续排空管道并标记 truncated。 */
    private static final class CapturedOutput {
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;

        private CapturedOutput(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized void drain(InputStream input) throws IOException {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) {
                int remaining = maxBytes - buffer.size();
                if (remaining > 0) {
                    buffer.write(chunk, 0, Math.min(read, remaining));
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
        }

        synchronized String text() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}
