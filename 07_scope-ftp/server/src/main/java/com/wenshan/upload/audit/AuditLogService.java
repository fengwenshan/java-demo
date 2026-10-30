package com.wenshan.upload.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.security.CachedBodyHttpServletRequest;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 操作审计日志：每请求一条 JSONL（{@code audit-yyyy-MM-dd.jsonl}），异步队列写、失败不影响请求。
 *
 * <p>目录默认 {@code ./logs/audit}（不在任何 storage root 内）；启动创建并做可写探测，
 * 不可用时禁用审计并记录 error（不让服务启动失败、也不影响业务）。队列满丢弃并计数告警；
 * {@link PreDestroy} 时 flush 全部队列。历史文件按 {@code max-history-days} 每日后台清理。</p>
 */
@Component
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final String FILE_PREFIX = "audit-";
    private static final String FILE_SUFFIX = ".jsonl";
    private static final int QUEUE_CAPACITY = 4096;
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Pattern DAY_FILE = Pattern.compile("^audit-(\\d{4}-\\d{2}-\\d{2})\\.jsonl$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties properties;
    private final AuditStore auditStore;
    private final Path dir;
    private final int maxBodyChars;
    private final int maxHistoryDays;
    private final BlockingQueue<AuditEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean active;
    private volatile boolean running;
    private Thread writerThread;
    private ScheduledExecutorService cleaner;
    private BufferedWriter writer;
    private LocalDate writerDate;

    public AuditLogService(AppProperties properties, AuditStore auditStore) {
        this.properties = properties;
        this.auditStore = auditStore;
        AppProperties.Audit config = properties.getAudit();
        String configuredDir = config.getDir() == null || config.getDir().isBlank()
                ? "./logs/audit" : config.getDir().trim();
        this.dir = Path.of(configuredDir).toAbsolutePath().normalize();
        this.maxBodyChars = Math.max(0, config.getMaxBodyChars());
        this.maxHistoryDays = Math.max(1, config.getMaxHistoryDays());
        if (!config.isEnabled()) {
            this.active = false;
            log.info("审计日志已禁用（app.audit.enabled=false）");
            return;
        }
        try {
            Files.createDirectories(dir);
            verifyWritable(dir);
        } catch (IOException | RuntimeException e) {
            this.active = false;
            log.error("审计日志目录不可用，审计功能已禁用: {} ({})", dir, e.toString());
            return;
        }
        this.active = true;
        this.running = true;
        this.writerThread = new Thread(this::writerLoop, "audit-log-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
        this.cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "audit-log-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        this.cleaner.scheduleWithFixedDelay(this::cleanupSafely, 1, 1, TimeUnit.HOURS);
        log.info("审计日志已启用: dir={}, 每日文件={}yyyy-MM-dd{}, SQLite 查询={}, 保留 {} 天",
                dir, FILE_PREFIX, FILE_SUFFIX, auditStore.isAvailable() ? auditStore.dbPath() : "禁用", maxHistoryDays);
        cleanupSafely(); // 启动时同步清理一次（JSONL + SQLite），失败不影响启动
    }

    public boolean isActive() {
        return active;
    }

    public long droppedCount() {
        return dropped.get();
    }

    public String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** 记录一条审计事件；队列满/服务禁用时静默丢弃（计数+告警），绝不影响请求。 */
    public void record(AuditEvent event) {
        if (!active || event == null) {
            return;
        }
        if (!queue.offer(event)) {
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 100 == 0) {
                log.warn("审计日志队列已满，丢弃事件（累计 {} 条）", total);
            }
        }
    }

    /**
     * 在 filter 中统一构造事件。请求体只读取经过签名过滤器缓存的 JSON body；
     * multipart/octet-stream 不读取，仅记录 Content-Length 与 X-Content-Sha256。
     */
    public AuditEvent buildEvent(HttpServletRequest request, HttpServletResponse response,
                                 String requestId, long startNanos, int httpStatus, boolean success,
                                 String error, String appKey, String clientName, String deviceId) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        String action = AuditAction.resolve(method, path);
        String rawQuery = request.getQueryString();
        byte[] bodyBytes = null;
        if (request instanceof CachedBodyHttpServletRequest cached) {
            bodyBytes = cached.getCachedBody();
        }
        AuditSanitizer.Meta meta = AuditSanitizer.parseMeta(bodyBytes, rawQuery);
        String body = bodyBytes == null ? null : AuditSanitizer.sanitizeBody(bodyBytes, maxBodyChars);
        String query = AuditSanitizer.sanitizeQuery(rawQuery);
        Long requestBytes = bodyBytes != null ? Long.valueOf(bodyBytes.length) : contentLength(request);
        Long responseBytes = headerLong(response, "Content-Length");
        String contentSha = request.getHeader("X-Content-Sha256");
        // multipart 上传的 body 不缓存，由 controller 把实际落盘文件名回填到 request attribute。
        String fileName = AuditContext.fileName(request);
        if (fileName == null || fileName.isBlank()) {
            fileName = meta.newName();
        }
        long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        return new AuditEvent(
                OffsetDateTime.now().format(TS_FORMAT),
                requestId,
                appKey,
                clientName,
                deviceId,
                clientIp(request),
                method,
                path,
                query,
                action,
                meta.root(),
                meta.targetRoot(),
                meta.pathParam(),
                meta.targetDir(),
                meta.sessionId(),
                meta.terminalCommand(),
                body,
                contentSha,
                requestBytes,
                responseBytes,
                durationMs,
                httpStatus,
                success,
                error,
                request.getHeader("User-Agent"),
                fileName);
    }

    private void writerLoop() {
        try {
            while (running || !queue.isEmpty()) {
                AuditEvent event = queue.poll(500, TimeUnit.MILLISECONDS);
                if (event == null) {
                    flushWriter();
                    continue;
                }
                writeEvent(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            flushWriter();
            closeWriter();
        }
    }

    private void writeEvent(AuditEvent event) {
        String line;
        try {
            line = MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("审计事件序列化失败: {}", e.toString());
            return;
        }
        try {
            LocalDate date = LocalDate.parse(OffsetDateTime.parse(event.ts()).format(DateTimeFormatter.ISO_LOCAL_DATE));
            if (writer == null || !date.equals(writerDate)) {
                closeWriter();
                Path file = dir.resolve(FILE_PREFIX + date.format(DAY_FORMAT) + FILE_SUFFIX);
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND,
                        java.nio.file.StandardOpenOption.WRITE);
                writerDate = date;
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException | RuntimeException e) {
            log.warn("写入审计 JSONL 失败: dir={} ({})", dir, e.toString());
            closeWriter();
        }
        // SQLite 作为客户端查询数据源与 JSONL 双写；DB 失败不影响 JSONL/请求。
        auditStore.insert(event);
    }

    private void flushWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            log.warn("flush 审计日志失败: {}", e.toString());
            closeWriter();
        }
    }

    private void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("关闭审计日志文件失败: {}", e.toString());
        } finally {
            writer = null;
            writerDate = null;
        }
    }

    private void cleanupSafely() {
        try {
            int deleted = cleanupHistory(dir, maxHistoryDays, LocalDate.now(ZoneId.systemDefault()));
            int deletedRows = auditStore.cleanup(maxHistoryDays);
            if (deleted > 0 || deletedRows > 0) {
                log.info("清理过期审计日志 {} 个文件、{} 行 SQLite（保留 {} 天）",
                        deleted, deletedRows, maxHistoryDays);
            }
        } catch (RuntimeException e) {
            log.warn("清理过期审计日志失败: {}", e.toString());
        }
    }

    /** 删除文件名日期早于 {@code today - maxHistoryDays} 的审计文件；返回删除数量（单测可调）。 */
    public static int cleanupHistory(Path dir, int maxHistoryDays, LocalDate today) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        LocalDate cutoff = today.minusDays(Math.max(1, maxHistoryDays));
        int deleted = 0;
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                Matcher matcher = DAY_FILE.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                LocalDate date;
                try {
                    date = LocalDate.parse(matcher.group(1));
                } catch (DateTimeParseException e) {
                    continue;
                }
                if (date.isBefore(cutoff)) {
                    try {
                        Files.deleteIfExists(file);
                        deleted++;
                    } catch (IOException e) {
                        log.warn("删除过期审计日志失败: {} ({})", file, e.toString());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("遍历审计日志目录失败: {} ({})", dir, e.toString());
        }
        return deleted;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushWriter();
        closeWriter();
    }

    private static void verifyWritable(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("不是目录: " + dir);
        }
        Path probe = Files.createTempFile(dir, ".audit-write-check-", ".tmp");
        try {
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            log.warn("删除审计写权限探测文件失败: {}", probe);
        }
    }

    private static Long contentLength(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        return length >= 0 ? length : null;
    }

    private static Long headerLong(HttpServletResponse response, String name) {
        if (response == null) {
            return null;
        }
        String value = response.getHeader(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
