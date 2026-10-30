package com.wenshan.upload.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 远程终端高危命令护栏（契约 3.21）。
 *
 * <p><b>重要：这是护栏不是沙箱。</b>规则只能降低误操作/简单攻击风险，无法阻止有意绕过
 * （shell 语法极其灵活）。终端功能默认只应在本机/内网开放；暴露公网或多人共用环境请关闭
 * {@code app.terminal.enabled}。</p>
 *
 * <p>匹配策略：命令先做规范化（trim、去引号、转小写、压缩空白），按 shell 分隔符
 * {@code ; && || | & 换行} 做「简单引号感知」分段，然后对<b>整条命令</b>与<b>每个分段</b>
 * 同时匹配全部规则，避免 {@code ls; rm -rf /} 这类链式命令漏检。每条规则都带中文原因。</p>
 */
public final class TerminalGuard {

    /** 与契约一致的单条命令最大长度。 */
    public static final int MAX_COMMAND_LENGTH = 4096;

    private static final Set<String> WRAPPERS = Set.of("env", "command", "builtin", "nohup", "time", "nice");

    /** 以「程序名」为单位拦截：命中即整条命令拒绝（含提权/系统管理/销毁类）。 */
    private static final Map<String, String> BLOCKED_PROGRAMS = Map.ofEntries(
            Map.entry("sudo", "使用提权命令（sudo）执行任意命令"),
            Map.entry("doas", "使用提权命令（doas）执行任意命令"),
            Map.entry("su", "切换用户身份（su）"),
            Map.entry("passwd", "修改用户密码（passwd）"),
            Map.entry("chpasswd", "批量修改用户密码（chpasswd）"),
            Map.entry("useradd", "新增系统用户（useradd）"),
            Map.entry("userdel", "删除系统用户（userdel）"),
            Map.entry("usermod", "修改系统用户（usermod）"),
            Map.entry("groupadd", "新增系统用户组（groupadd）"),
            Map.entry("groupdel", "删除系统用户组（groupdel）"),
            Map.entry("killall", "按名称批量终止进程（killall）"),
            Map.entry("pkill", "按名称终止进程（pkill，可能误杀关键进程）"),
            Map.entry("iptables", "修改防火墙规则（iptables）"),
            Map.entry("ip6tables", "修改防火墙规则（ip6tables）"),
            Map.entry("nft", "修改防火墙规则（nft）"),
            Map.entry("ufw", "修改防火墙规则（ufw）"),
            Map.entry("modprobe", "加载/卸载内核模块（modprobe）"),
            Map.entry("insmod", "加载内核模块（insmod）"),
            Map.entry("rmmod", "卸载内核模块（rmmod）"),
            Map.entry("sysctl", "修改内核参数（sysctl）"),
            Map.entry("mount", "挂载文件系统（mount）"),
            Map.entry("umount", "卸载文件系统（umount）"),
            Map.entry("chroot", "切换根目录（chroot，提权逃逸风险）"),
            Map.entry("nsenter", "进入其他命名空间（nsenter，提权逃逸风险）"),
            Map.entry("shutdown", "关机（shutdown）"),
            Map.entry("reboot", "重启系统（reboot）"),
            Map.entry("halt", "停机（halt）"),
            Map.entry("poweroff", "关机（poweroff）"),
            Map.entry("fdisk", "修改磁盘分区表（fdisk）"),
            Map.entry("parted", "修改磁盘分区表（parted）"),
            Map.entry("diskutil", "macOS 磁盘/分区操作（diskutil），可能销毁数据"),
            Map.entry("shred", "覆写销毁文件（shred）"));

    /** 全串/分段正则规则（顺序即优先级）。 */
    private static final List<PatternRule> PATTERN_RULES = List.of(
            new PatternRule("fork 炸弹（耗尽系统进程）",
                    Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{[^}]*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:")),
            new PatternRule("将内容通过管道交给 shell 执行（远程代码执行风险）",
                    Pattern.compile("\\|\\s*(?:sudo\\s+)?(?:sh|bash|zsh|dash|ksh|csh|fish)\\b")),
            new PatternRule("将内容通过管道交给解释器执行（远程代码执行风险）",
                    Pattern.compile("\\|\\s*(?:python[0-9.]*|perl|ruby|node|php)\\b")),
            new PatternRule("执行远程下载内容（eval $(curl/wget ...)）",
                    Pattern.compile("\\beval\\b[^\\n]*\\$\\(\\s*(?:curl|wget)\\b")),
            new PatternRule("直接写入磁盘设备（可能损坏磁盘）",
                    Pattern.compile(">>?\\s*/dev/(?:disk|rdisk|sd|nvme|hd|ad|mmcblk)")),
            new PatternRule("重定向写入系统目录（可能破坏系统文件）",
                    Pattern.compile(">>?\\s*/(?:etc|usr|bin|sbin|boot|lib|lib64|system|library"
                            + "|applications|private/etc)(?:/|\\s|$)")));

    private TerminalGuard() {
    }

    /**
     * 检查命令是否命中高危规则。
     *
     * @return 命中时返回中文原因；允许执行（或空命令，由调用方校验）时返回 {@link Optional#empty()}
     */
    public static Optional<String> forbiddenReason(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return Optional.empty();
        }
        List<String> probes = new ArrayList<>();
        probes.add(normalize(rawCommand));
        for (String segment : segments(rawCommand)) {
            String normalized = normalize(segment);
            if (!normalized.isEmpty()) {
                probes.add(normalized);
            }
        }
        for (String probe : probes) {
            Optional<String> reason = checkProbe(probe);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    /** 简单引号感知分段：按 ; && || | & 与换行切分（引号内的分隔符不切）。 */
    public static List<String> segments(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ';' || c == '\n' || c == '\r') {
                flush(result, current);
                continue;
            }
            if (c == '&') {
                if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    i++;
                }
                flush(result, current);
                continue;
            }
            if (c == '|') {
                if (i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    i++;
                }
                flush(result, current);
                continue;
            }
            current.append(c);
        }
        flush(result, current);
        return result;
    }

    private static void flush(List<String> result, StringBuilder current) {
        String segment = current.toString().trim();
        if (!segment.isEmpty()) {
            result.add(segment);
        }
        current.setLength(0);
    }

    private static String normalize(String command) {
        return command.trim()
                .replace("'", "")
                .replace("\"", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static Optional<String> checkProbe(String probe) {
        List<String> tokens = tokens(probe);
        String program = firstProgram(tokens);

        Optional<String> reason = checkRm(tokens);
        if (reason.isPresent()) {
            return reason;
        }
        reason = checkFind(tokens, program);
        if (reason.isPresent()) {
            return reason;
        }
        reason = checkRecursivePermission(tokens, program);
        if (reason.isPresent()) {
            return reason;
        }
        reason = checkDd(tokens, program);
        if (reason.isPresent()) {
            return reason;
        }
        if (program != null) {
            String blockedReason = BLOCKED_PROGRAMS.get(program);
            if (blockedReason != null) {
                return Optional.of(blockedReason);
            }
            if (program.startsWith("mkfs")) {
                return Optional.of("格式化文件系统（mkfs）");
            }
        }
        reason = checkSystemPowerAndInit(tokens, program);
        if (reason.isPresent()) {
            return reason;
        }
        reason = checkKillInit(tokens, program);
        if (reason.isPresent()) {
            return reason;
        }
        reason = checkCrontabAndHistory(tokens, program);
        if (reason.isPresent()) {
            return reason;
        }
        for (PatternRule rule : PATTERN_RULES) {
            if (rule.pattern().matcher(probe).find()) {
                return Optional.of(rule.reason());
            }
        }
        return Optional.empty();
    }

    /** rm 递归删除：-r/-R 与 -f 等的组合 + 危险目标，或 --no-preserve-root。 */
    private static Optional<String> checkRm(List<String> tokens) {
        int index = indexOfProgram(tokens, "rm");
        if (index < 0) {
            return Optional.empty();
        }
        boolean recursive = false;
        boolean noPreserveRoot = false;
        List<String> targets = new ArrayList<>();
        for (int i = index + 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("--")) {
                if (token.equals("--no-preserve-root")) {
                    noPreserveRoot = true;
                } else if (token.startsWith("--recursive")) {
                    recursive = true;
                }
                continue;
            }
            if (token.startsWith("-") && token.length() > 1) {
                String flags = token.substring(1);
                recursive = recursive || flags.contains("r") || flags.contains("R");
                continue;
            }
            targets.add(token);
        }
        if (noPreserveRoot) {
            return Optional.of("使用 --no-preserve-root 递归删除系统根目录");
        }
        if (!recursive || targets.isEmpty()) {
            return Optional.empty();
        }
        for (String target : targets) {
            if (isDangerousAbsoluteOrWildcard(target)) {
                return Optional.of("递归删除系统根目录/家目录/当前目录（rm -rf）");
            }
        }
        return Optional.empty();
    }

    /** find / -delete、find / -exec rm/shred。 */
    private static Optional<String> checkFind(List<String> tokens, String program) {
        if (!"find".equals(program)) {
            return Optional.empty();
        }
        boolean delete = false;
        boolean execRemove = false;
        boolean dangerousTarget = false;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("-delete".equals(token)) {
                delete = true;
            }
            if ("-exec".equals(token) && i + 1 < tokens.size()
                    && ("rm".equals(tokens.get(i + 1)) || "shred".equals(tokens.get(i + 1)))) {
                execRemove = true;
            }
            if (token.startsWith("/") || "~".equals(token) || "$home".equals(token)
                    || "${home}".equals(token)) {
                dangerousTarget = true;
            }
        }
        if ((delete || execRemove) && dangerousTarget) {
            return Optional.of("递归删除文件（find / -delete / -exec rm）");
        }
        return Optional.empty();
    }

    /** chmod/chown/chgrp 递归修改根目录或顶层系统目录权限/属主。 */
    private static Optional<String> checkRecursivePermission(List<String> tokens, String program) {
        if (program == null || !("chmod".equals(program) || "chown".equals(program) || "chgrp".equals(program))) {
            return Optional.empty();
        }
        boolean recursive = false;
        boolean dangerousTarget = false;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            // 规范化后命令已转小写，这里同时兼容 -r/-R
            String flags = token.startsWith("-") && !token.startsWith("--") ? token.substring(1) : "";
            if ("-R".equals(token) || "-r".equals(token) || "--recursive".equals(token)
                    || flags.contains("r")) {
                recursive = true;
            } else if (isRootOrTopLevelAbsolute(token)) {
                dangerousTarget = true;
            }
        }
        if (recursive && dangerousTarget) {
            return Optional.of("递归修改系统目录权限/属主（chmod/chown -R 根目录）");
        }
        return Optional.empty();
    }

    /** dd 直接写裸设备（dd of=/dev/...）。 */
    private static Optional<String> checkDd(List<String> tokens, String program) {
        if (!"dd".equals(program)) {
            return Optional.empty();
        }
        for (String token : tokens) {
            if (token.startsWith("of=/dev/")) {
                return Optional.of("直接写裸设备（dd of=/dev/...），可能损坏磁盘");
            }
        }
        return Optional.empty();
    }

    /** init 0/6、systemctl poweroff/reboot/halt。 */
    private static Optional<String> checkSystemPowerAndInit(List<String> tokens, String program) {
        if ("init".equals(program)) {
            for (int i = 1; i < tokens.size(); i++) {
                if ("0".equals(tokens.get(i)) || "6".equals(tokens.get(i))) {
                    return Optional.of("关机/重启系统（init 0/6）");
                }
            }
        }
        if ("systemctl".equals(program)) {
            for (int i = 1; i < tokens.size(); i++) {
                String sub = tokens.get(i);
                if (sub.equals("poweroff") || sub.equals("reboot") || sub.equals("halt")
                        || sub.equals("poweroff.target") || sub.equals("reboot.target")
                        || sub.equals("halt.target")) {
                    return Optional.of("关机/重启系统（systemctl " + sub + "）");
                }
            }
        }
        return Optional.empty();
    }

    /** kill -9 1（杀 init/launchd）。 */
    private static Optional<String> checkKillInit(List<String> tokens, String program) {
        if (!"kill".equals(program)) {
            return Optional.empty();
        }
        for (String token : tokens) {
            if ("1".equals(token)) {
                return Optional.of("终止 PID 1（init/launchd），会导致系统不可用");
            }
        }
        return Optional.empty();
    }

    /** crontab -r、history -c。 */
    private static Optional<String> checkCrontabAndHistory(List<String> tokens, String program) {
        if ("crontab".equals(program)) {
            for (String token : tokens) {
                if ("-r".equals(token)) {
                    return Optional.of("清空定时任务（crontab -r）");
                }
            }
        }
        if ("history".equals(program)) {
            for (String token : tokens) {
                if ("-c".equals(token)) {
                    return Optional.of("清空命令历史（history -c）");
                }
            }
        }
        return Optional.empty();
    }

    /** 危险目标：/ 本身、任意绝对顶层目录（/etc、/usr…）、~、$HOME、.、..、*（含 /* 形式）。 */
    private static boolean isDangerousAbsoluteOrWildcard(String rawTarget) {
        String target = rawTarget;
        if (target.endsWith("/*")) {
            target = target.substring(0, target.length() - 2);
        }
        while (target.endsWith("/") && target.length() > 1) {
            target = target.substring(0, target.length() - 1);
        }
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.equals(".") || lower.equals("..") || lower.equals("*")
                || lower.equals("~") || lower.equals("$home") || lower.equals("${home}") || lower.isEmpty()) {
            return true;
        }
        if (lower.startsWith("/")) {
            return lower.equals("/") || lower.matches("^/[^/]+$");
        }
        return false;
    }

    private static boolean isRootOrTopLevelAbsolute(String token) {
        String target = token;
        while (target.endsWith("/") && target.length() > 1) {
            target = target.substring(0, target.length() - 1);
        }
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.equals("/")) {
            return true;
        }
        if (lower.startsWith("/")) {
            return lower.matches("^/[^/]+$");
        }
        return false;
    }

    private static List<String> tokens(String probe) {
        String[] parts = probe.trim().split("\\s+");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    /** 找到命令分段中第一个「真正的程序名」（跳过环境变量赋值与 env/command/nohup 等包装）。 */
    private static String firstProgram(List<String> tokens) {
        for (String token : tokens) {
            if (token.startsWith("-")) {
                continue;
            }
            if (!token.startsWith("=") && token.contains("=")) {
                continue;
            }
            if (WRAPPERS.contains(token)) {
                continue;
            }
            return token;
        }
        return null;
    }

    private static int indexOfProgram(List<String> tokens, String program) {
        for (int i = 0; i < tokens.size(); i++) {
            if (program.equals(tokens.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private record PatternRule(String reason, Pattern pattern) {
    }

    /** 供文档/客户端展示的规则条数（不用于判定）。 */
    public static int ruleCount() {
        return BLOCKED_PROGRAMS.size() + PATTERN_RULES.size();
    }
}
