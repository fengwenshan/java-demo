package com.wenshan.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端全部可配置项，前缀 {@code app}。
 * 支持环境变量覆盖（见 application.yml 中的 ${UPLOAD_*} 占位符）。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Security security = new Security();
    private final Storage storage = new Storage();
    private final Terminal terminal = new Terminal();
    private final Audit audit = new Audit();

    public Security getSecurity() {
        return security;
    }

    public Storage getStorage() {
        return storage;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public Audit getAudit() {
        return audit;
    }

    public static class Security {

        /** 允许的时间戳偏差（毫秒），默认 ±5 分钟。 */
        private long timestampWindowMs = 300_000L;

        /** nonce 缓存 TTL（毫秒），默认 10 分钟。 */
        private long nonceTtlMs = 600_000L;

        /** 是否开启设备绑定校验（本地开发默认关闭，生产建议通过环境变量开启）。 */
        private boolean deviceBindingEnabled = false;

        private final RateLimit rateLimit = new RateLimit();

        private List<Client> clients = new ArrayList<>();

        public long getTimestampWindowMs() {
            return timestampWindowMs;
        }

        public void setTimestampWindowMs(long timestampWindowMs) {
            this.timestampWindowMs = timestampWindowMs;
        }

        public long getNonceTtlMs() {
            return nonceTtlMs;
        }

        public void setNonceTtlMs(long nonceTtlMs) {
            this.nonceTtlMs = nonceTtlMs;
        }

        public boolean isDeviceBindingEnabled() {
            return deviceBindingEnabled;
        }

        public void setDeviceBindingEnabled(boolean deviceBindingEnabled) {
            this.deviceBindingEnabled = deviceBindingEnabled;
        }

        public RateLimit getRateLimit() {
            return rateLimit;
        }

        public List<Client> getClients() {
            return clients;
        }

        public void setClients(List<Client> clients) {
            this.clients = clients == null ? new ArrayList<>() : clients;
        }
    }

    public static class RateLimit {

        /** 令牌桶容量。 */
        private int capacity = 1000;

        /** 每秒补充令牌数。 */
        private double refillPerSecond = 1.0D;

        /** 上传接口单次消耗令牌数。 */
        private int uploadCost = 5;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }

        public int getUploadCost() {
            return uploadCost;
        }

        public void setUploadCost(int uploadCost) {
            this.uploadCost = uploadCost;
        }
    }

    public static class Client {

        private String appKey;

        private String appSecret;

        private String name = "";

        private boolean enabled = true;

        public Client() {
        }

        public Client(String appKey, String appSecret, String name, boolean enabled) {
            this.appKey = appKey;
            this.appSecret = appSecret;
            this.name = name;
            this.enabled = enabled;
        }

        public String getAppKey() {
            return appKey;
        }

        public void setAppKey(String appKey) {
            this.appKey = appKey;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Storage {

        /** 默认根 id / 展示名 / 路径（与 application.yml 的默认 roots 保持一致）。 */
        public static final String DEFAULT_ROOT_ID = "default";
        public static final String DEFAULT_ROOT_NAME = "默认目录";
        public static final String DEFAULT_ROOT_PATH = "./data";

        /**
         * 多存储根目录（唯一存储配置）：第一个根为默认根（接口不传 root 时使用）。
         * 根下只放用户的真实文件/目录；每个根内部元数据在隐藏目录 &lt;root&gt;/.upload-meta/。
         *
         * <p>也可用环境变量 {@code UPLOAD_STORAGE_ROOTS} 覆盖，格式 {@code id=path,id2=path2}
         * （简单 demo 形式；复杂场景请用 application.yml/外部配置）。
         * 显式配置为空列表时启动失败（不再隐式创建单根）。</p>
         */
        private List<Root> roots = new ArrayList<>(List.of(
                new Root(DEFAULT_ROOT_ID, DEFAULT_ROOT_NAME, DEFAULT_ROOT_PATH)));

        /** 单文件大小上限（字节），默认 5GB；单请求 multipart 仍受 max-request-size（210MB）限制。 */
        private long maxFileSize = 5_368_709_120L;

        /** 分片上传单片大小（字节），默认 8MB；由 POST /api/files/upload/init 原样返回给客户端。 */
        private int chunkSize = 8_388_608;

        /** 分片上传会话保留时长（毫秒），默认 24 小时（启动时清理过期会话）。 */
        private long sessionTtlMs = 86_400_000L;

        /** 在线编辑文本上限（UTF-8 字节），默认 2MiB；超过则拒绝读取/保存。 */
        private long maxEditBytes = 2_097_152L;

        /** zip 解压最多 entry 数（含目录 entry），默认 20000，防 zip bomb。 */
        private int maxExtractEntries = 20_000;

        /** zip 解压总字节上限，默认 1GiB，防 zip bomb。 */
        private long maxExtractTotalBytes = 1_073_741_824L;

        /** zip 单 entry 解压字节上限，默认 200MiB。 */
        private long maxExtractEntryBytes = 209_715_200L;

        public List<Root> getRoots() {
            return roots;
        }

        public void setRoots(List<Root> roots) {
            this.roots = roots == null ? new ArrayList<>() : roots;
        }

        public long getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public long getSessionTtlMs() {
            return sessionTtlMs;
        }

        public void setSessionTtlMs(long sessionTtlMs) {
            this.sessionTtlMs = sessionTtlMs;
        }

        public long getMaxEditBytes() {
            return maxEditBytes;
        }

        public void setMaxEditBytes(long maxEditBytes) {
            this.maxEditBytes = maxEditBytes;
        }

        public int getMaxExtractEntries() {
            return maxExtractEntries;
        }

        public void setMaxExtractEntries(int maxExtractEntries) {
            this.maxExtractEntries = maxExtractEntries;
        }

        public long getMaxExtractTotalBytes() {
            return maxExtractTotalBytes;
        }

        public void setMaxExtractTotalBytes(long maxExtractTotalBytes) {
            this.maxExtractTotalBytes = maxExtractTotalBytes;
        }

        public long getMaxExtractEntryBytes() {
            return maxExtractEntryBytes;
        }

        public void setMaxExtractEntryBytes(long maxExtractEntryBytes) {
            this.maxExtractEntryBytes = maxExtractEntryBytes;
        }

    }

    /** 单个存储根目录配置（{@code app.storage.roots[]}）。 */
    public static class Root {

        /** 根 id（接口 root 参数使用）：{@code ^[a-zA-Z0-9_-]{1,32}$}，必须唯一。 */
        private String id;

        /** 展示名（可选，默认= id）。 */
        private String name;

        /** 根目录路径（绝对或相对，启动时解析为绝对路径；不存在则创建）。 */
        private String path;

        public Root() {
        }

        public Root(String id, String name, String path) {
            this.id = id;
            this.name = name;
            this.path = path;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    /**
     * 远程服务器终端配置（契约 3.21）。默认开启，仅建议本机/内网使用；
     * 暴露公网或多人共用环境时请设置 UPLOAD_TERMINAL_ENABLED=false。
     */
    public static class Terminal {

        /** 终端功能总开关，默认 true。 */
        private boolean enabled = true;

        /** 单条命令超时（秒），默认 10，服务端按 1~60 收敛。 */
        private int timeoutSeconds = 10;

        /** 输出上限（字节），默认 256KiB（262144），超出截断并标记 truncated。 */
        private int maxOutputBytes = 262_144;

        /** 最大并发会话数，默认 50，超出按最久未用淘汰。 */
        private int maxSessions = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxOutputBytes() {
            return maxOutputBytes;
        }

        public void setMaxOutputBytes(int maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
        }

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
        }
    }

    /**
     * 操作审计日志（客户端触发的全部 /api/** 请求，含成功与失败）。
     * 只落盘 JSONL，不提供查询接口；默认目录 ./logs/audit，按天分文件。
     */
    public static class Audit {

        /** 是否启用审计日志。 */
        private boolean enabled = true;

        /** 审计日志目录（默认 ./logs/audit，不在任何存储根内）。 */
        private String dir = "./logs/audit";

        /** 请求体脱敏后序列化并截断的最大字符数。 */
        private int maxBodyChars = 4000;

        /** 审计日志保留天数（按天文件，超期后台清理）。 */
        private int maxHistoryDays = 90;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public int getMaxBodyChars() {
            return maxBodyChars;
        }

        public void setMaxBodyChars(int maxBodyChars) {
            this.maxBodyChars = maxBodyChars;
        }

        public int getMaxHistoryDays() {
            return maxHistoryDays;
        }

        public void setMaxHistoryDays(int maxHistoryDays) {
            this.maxHistoryDays = maxHistoryDays;
        }
    }

}
