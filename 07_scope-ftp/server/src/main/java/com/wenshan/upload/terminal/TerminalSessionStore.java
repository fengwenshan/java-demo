package com.wenshan.upload.terminal;

import com.wenshan.upload.config.AppProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 终端会话状态：按客户端生成的 sessionId 维护 cwd（含 {@code cd -} 的上一目录），
 * TTL 30 分钟懒清理，会话数超过 max-sessions 时淘汰最久未用。
 *
 * <p>会话 cwd 只能是 {@link TerminalScope} 允许根内的目录（真实路径）。</p>
 */
@Component
public class TerminalSessionStore {

    /** 会话 TTL：30 分钟（契约 3.21）。 */
    public static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private final Map<String, Session> sessions = new HashMap<>();
    private final int maxSessions;
    private final TerminalScope scope;

    public TerminalSessionStore(AppProperties properties, TerminalScope scope) {
        this.maxSessions = Math.max(1, properties.getTerminal().getMaxSessions());
        this.scope = scope;
    }

    /**
     * 取会话；不存在则创建。首次 cwd 优先级：
     * {@code requestedCwd}（必须在允许根内）→ 合法 {@code root} id 对应根 → 默认第一个根。
     * 已存在的会话忽略请求的 cwd/root，一律以服务端维护的 cwd 为准。
     */
    public synchronized Session getOrCreate(String sessionId, String requestedCwd, String rootParam) {
        long now = System.currentTimeMillis();
        purgeExpired(now);
        Session existing = sessions.get(sessionId);
        if (existing != null) {
            existing.touch(now);
            return existing;
        }
        Path cwd = requestedCwd == null || requestedCwd.isBlank()
                ? scope.rootPath(rootParam)
                : scope.requireInitialCwd(requestedCwd);
        if (sessions.size() >= maxSessions) {
            evictLeastRecentlyUsed();
        }
        Session session = new Session(sessionId, cwd, now);
        sessions.put(sessionId, session);
        return session;
    }

    public int sessionCount() {
        synchronized (this) {
            purgeExpired(System.currentTimeMillis());
            return sessions.size();
        }
    }

    /** 会话状态（cwd 可变；previousCwd 用于 {@code cd -}）。 */
    public static final class Session {
        private final String id;
        private Path cwd;
        private Path previousCwd;
        private long lastUsedAt;

        private Session(String id, Path cwd, long lastUsedAt) {
            this.id = id;
            this.cwd = cwd;
            this.lastUsedAt = lastUsedAt;
        }

        public String id() {
            return id;
        }

        public Path cwd() {
            return cwd;
        }

        public Path previousCwd() {
            return previousCwd;
        }

        public long lastUsedAt() {
            return lastUsedAt;
        }

        void touch(long now) {
            this.lastUsedAt = now;
        }

        public void changeCwd(Path next) {
            this.previousCwd = this.cwd;
            this.cwd = next;
        }
    }

    private void purgeExpired(long now) {
        sessions.values().removeIf(session -> now - session.lastUsedAt > SESSION_TTL.toMillis());
    }

    private void evictLeastRecentlyUsed() {
        Optional<Session> oldest = sessions.values().stream()
                .min(Comparator.comparingLong(Session::lastUsedAt));
        oldest.ifPresent(session -> sessions.remove(session.id()));
    }
}
