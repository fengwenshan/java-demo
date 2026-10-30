package com.wenshan.upload.security;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 契约 2.4 查询串规范化。
 *
 * <ol>
 *   <li>按 &amp; 拆分为键值对，每对按第一个 = 拆分（无 value 视为空串）</li>
 *   <li>key / value 做 percent-decoding（UTF-8，+ 不当作空格）</li>
 *   <li>重新做 RFC3986 percent-encoding，仅保留 A-Z a-z 0-9 - . _ ~</li>
 *   <li>先按 key 升序、key 相同按 value 升序排序</li>
 *   <li>用 &amp; 连接 key=value；无参数时为空字符串</li>
 * </ol>
 */
public final class CanonicalQuery {

    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    private CanonicalQuery() {
    }

    public static String canonicalize(String rawQueryString) {
        if (rawQueryString == null || rawQueryString.isEmpty()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String pair : rawQueryString.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String rawKey = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            pairs.add(new String[]{
                    encode(decode(rawKey)),
                    encode(decode(rawValue))
            });
        }
        pairs.sort(Comparator.comparing((String[] pair) -> pair[0]).thenComparing(pair -> pair[1]));

        StringBuilder result = new StringBuilder();
        for (String[] pair : pairs) {
            if (!result.isEmpty()) {
                result.append('&');
            }
            result.append(pair[0]).append('=').append(pair[1]);
        }
        return result.toString();
    }

    /** percent-decoding：只识别 %XX，其余字符按 UTF-8 字节原样保留（+ 不转空格）。 */
    static byte[] decode(String raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
        int index = 0;
        while (index < raw.length()) {
            char current = raw.charAt(index);
            if (current == '%' && index + 2 < raw.length()
                    && isHex(raw.charAt(index + 1)) && isHex(raw.charAt(index + 2))) {
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                out.write((high << 4) | low);
                index += 3;
            } else {
                int codePoint = raw.codePointAt(index);
                byte[] utf8 = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                out.writeBytes(utf8);
                index += Character.charCount(codePoint);
            }
        }
        return out.toByteArray();
    }

    /** RFC3986 percent-encoding：仅保留 unreserved 字符，其余字节编码为 %XX（大写）。 */
    static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) {
            int unsigned = value & 0xFF;
            if (isUnreserved(unsigned)) {
                out.append((char) unsigned);
            } else {
                out.append('%').append(HEX_UPPER[unsigned >>> 4]).append(HEX_UPPER[unsigned & 0x0F]);
            }
        }
        return out.toString();
    }

    private static boolean isUnreserved(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static boolean isHex(char value) {
        return Character.digit(value, 16) >= 0;
    }
}
