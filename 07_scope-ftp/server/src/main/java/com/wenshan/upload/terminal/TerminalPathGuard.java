package com.wenshan.upload.terminal;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端命令「非安全目录」静态防护（契约 3.21 收紧）：命令启动前扫描每段的路径参数/重定向目标，
 * 只允许访问 {@code app.storage.roots} 内的目录。
 *
 * <p>规则概要：</p>
 * <ul>
 *   <li>复用 {@link TerminalGuard#segments(String)} 的引号感知分段；每段第一个词是命令，
 *       允许 {@code /bin/ls}、{@code /usr/bin/env} 这类可执行绝对路径，不检查；</li>
 *   <li>其余词与重定向目标（{@code >}、{@code >>}、{@code <}、{@code 2>} 等）中，
 *       「路径型」词包括：以 {@code /} 开头、以 {@code ~}/{@code $HOME} 开头、含 {@code /}、
 *       含 {@code ..} 段；普通相对文件名（{@code a.txt}）视为 cwd 内安全；</li>
 *   <li>绝对/相对路径 normalize 后必须在允许根内；已存在路径（含最深已存在祖先）经
 *       {@code toRealPath()} 后仍须在根内，防 symlink 逃逸；</li>
 *   <li>{@code ~}、{@code ~/...}、{@code $HOME}、{@code ${HOME}} 一律阻止（家目录不在安全范围）。</li>
 * </ul>
 *
 * <p>这是护栏不是沙箱：命令本身（python/git 等）可能间接访问系统文件，无法完全阻止。</p>
 */
public final class TerminalPathGuard {

    /** 重定向：可选 fd 前缀 + 操作符 + 可选 &（fd 复制）+ 紧邻目标。 */
    private static final Pattern REDIRECT = Pattern.compile("^([0-9]*)(>>|>|<<?|<)(&?)(.*)$");
    private static final Pattern HOME_TOKEN = Pattern.compile("^\\$\\{?home}?(/.*)?$",
            Pattern.CASE_INSENSITIVE);

    private final List<Path> allowedRoots;
    private final Path sessionCwd;

    public TerminalPathGuard(List<Path> allowedRoots, Path sessionCwd) {
        this.allowedRoots = allowedRoots;
        this.sessionCwd = sessionCwd.toAbsolutePath().normalize();
    }

    /**
     * 扫描命令中的路径参数。
     *
     * @return 命中时返回越界的原始 token；安全时返回 {@link Optional#empty()}
     */
    public Optional<String> violation(String command) {
        for (String segment : TerminalGuard.segments(command)) {
            List<String> tokens = tokenize(segment);
            boolean commandSeen = false;
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (token.isEmpty()) {
                    continue;
                }
                Redirect redirect = parseRedirect(token);
                if (redirect != null) {
                    String target = redirect.attached();
                    if (target.isEmpty()) {
                        target = i + 1 < tokens.size() ? tokens.get(++i) : "";
                    }
                    if (target.isEmpty() || redirect.fdDup()) {
                        continue;
                    }
                    Optional<String> bad = checkPathToken(target);
                    if (bad.isPresent()) {
                        return bad;
                    }
                    continue;
                }
                if (!commandSeen) {
                    // 每段第一个词是命令本身（允许 /bin/ls 等可执行绝对路径）
                    commandSeen = true;
                    continue;
                }
                Optional<String> bad = checkPathToken(token);
                if (bad.isPresent()) {
                    return bad;
                }
            }
        }
        return Optional.empty();
    }

    /** 检查单个词；普通相对文件名（cwd 内）直接视为安全。 */
    private Optional<String> checkPathToken(String token) {
        String value = token;
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.startsWith("~") || HOME_TOKEN.matcher(value).matches()) {
            return Optional.of(token);
        }
        boolean pathLike = value.startsWith("/")
                || value.contains("/")
                || hasParentSegment(value);
        if (!pathLike) {
            return Optional.empty();
        }
        Path candidate;
        try {
            if (value.startsWith("/")) {
                candidate = Path.of(value).toAbsolutePath().normalize();
            } else {
                candidate = sessionCwd.resolve(value).toAbsolutePath().normalize();
            }
        } catch (InvalidPathException e) {
            return Optional.of(token);
        }
        return TerminalScope.realInsideAny(allowedRoots, candidate)
                ? Optional.empty()
                : Optional.of(token);
    }

    private static boolean hasParentSegment(String value) {
        for (String part : value.split("/")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    /** 识别重定向 token；非重定向返回 null。 */
    private static Redirect parseRedirect(String token) {
        String text = token;
        if (text.startsWith("&>")) {
            String attached = text.substring(2);
            return new Redirect(attached, false);
        }
        Matcher matcher = REDIRECT.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String attached = matcher.group(4);
        boolean fdDup = "&".equals(matcher.group(3));
        return new Redirect(attached, fdDup);
    }

    /** 引号感知分词：去掉引号，保留引号内空白为一个 token。 */
    static List<String> tokenize(String segment) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < segment.length()) {
                    current.append(segment.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '\\' && i + 1 < segment.length()) {
                current.append(segment.charAt(++i));
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private record Redirect(String attached, boolean fdDup) {
    }

    /** 统一错误消息（供 service 使用）。 */
    public static String message(String token) {
        return "命令涉及非安全目录：" + token + TerminalScope.MESSAGE_SUFFIX;
    }

    /** 家目录 token 是否命中（供单测/文档）。 */
    static boolean isHomeToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.startsWith("~") || HOME_TOKEN.matcher(token).matches();
    }
}
