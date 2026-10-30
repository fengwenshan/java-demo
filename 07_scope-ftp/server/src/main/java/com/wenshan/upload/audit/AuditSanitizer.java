package com.wenshan.upload.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 审计脱敏/截断/字段提取。
 *
 * <p>规则：JSON body 中字段名含 {@code secret}/{@code password}/{@code token}/{@code content}
 * （含 {@code appSecret}）统一替换为 {@code ***}；query 中敏感 key 的值同样替换；
 * 其余内容 JSON 序列化后按 {@code max-body-chars} 截断。签名/nonce 不落（filter 只取白名单字段）。</p>
 */
public final class AuditSanitizer {

    static final String MASK = "***";
    static final String TRUNCATED_SUFFIX = "...[truncated]";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditSanitizer() {
    }

    /** 从 JSON body 提取审计字段；body 非 JSON/为空时返回空 Meta（query 仍参与解析）。 */
    public static Meta parseMeta(byte[] body, String rawQuery) {
        JsonNode node = parseJson(body);
        Map<String, String> query = parseQuery(rawQuery);
        return new Meta(
                firstText(node, query, "root"),
                firstText(node, query, "targetRoot"),
                firstText(node, query, "path"),
                firstText(node, query, "targetDir"),
                firstText(node, query, "sessionId"),
                firstText(node, query, "command"),
                firstText(node, query, "newName"));
    }

    /** 脱敏后的 JSON 字符串；无法解析则返回 null（不做任何原文回退，避免泄漏）。 */
    public static String sanitizeBody(byte[] body, int maxChars) {
        JsonNode node = parseJson(body);
        if (node == null) {
            return null;
        }
        mask(node);
        String text;
        try {
            text = MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
        return truncate(text, maxChars);
    }

    /** query 中 secret/token/password/nonce/signature 的值替换为 ***，其余保留原编码。 */
    public static String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            int eq = part.indexOf('=');
            if (eq < 0) {
                out.append(part);
                continue;
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);
            if (isSensitiveKey(decoded(key)) && !value.isEmpty()) {
                out.append(key).append('=').append(MASK);
            } else {
                out.append(part);
            }
        }
        return out.toString();
    }

    static void mask(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> sensitive = new ArrayList<>();
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (isSensitiveKey(name)) {
                    sensitive.add(name);
                } else {
                    mask(object.get(name));
                }
            }
            for (String name : sensitive) {
                object.set(name, TextNode.valueOf(MASK));
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                mask(child);
            }
        }
    }

    static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("secret")
                || lower.contains("password")
                || lower.contains("token")
                || lower.equals("content")
                || lower.equals("nonce")
                || lower.equals("signature")
                || lower.equals("authorization");
    }

    static String truncate(String text, int maxChars) {
        if (text == null || maxChars < 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + TRUNCATED_SUFFIX;
    }

    private static JsonNode parseJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(JsonNode node, Map<String, String> query, String field) {
        if (node != null && node.isObject()) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return query.get(field);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            result.putIfAbsent(decoded(key), decoded(value));
        }
        return result;
    }

    private static String decoded(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return value;
        }
    }

    /** JSON body/query 中提取出的审计字段。 */
    public record Meta(String root, String targetRoot, String pathParam, String targetDir,
                       String sessionId, String terminalCommand, String newName) {
    }
}
