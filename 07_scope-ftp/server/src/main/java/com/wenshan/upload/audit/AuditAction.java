package com.wenshan.upload.audit;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 语义动作映射：把 {@code METHOD /api/...} 归一到固定 action 名称，未知路径 fallback {@code "METHOD path"}。
 */
public final class AuditAction {

    private static final Pattern BY_ID_DOWNLOAD = Pattern.compile("^/api/files/[^/]+/download$");
    private static final Pattern UPLOAD_CHUNK = Pattern.compile("^/api/files/upload/[^/]+/chunk$");
    private static final Pattern UPLOAD_COMPLETE = Pattern.compile("^/api/files/upload/[^/]+/complete$");
    private static final Pattern UPLOAD_STATUS = Pattern.compile("^/api/files/upload/[^/]+(?:/status)?$");
    private static final Pattern UPLOAD_CANCEL = Pattern.compile("^/api/files/upload/[^/]+$");
    private static final Pattern FILE_BY_ID = Pattern.compile("^/api/files/[^/]+$");

    private AuditAction() {
    }

    public static String resolve(String method, String path) {
        String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String uri = path == null ? "" : path;
        String exact = exactAction(verb, uri);
        if (exact != null) {
            return exact;
        }
        if ("GET".equals(verb) && BY_ID_DOWNLOAD.matcher(uri).matches()) {
            return "download.byId";
        }
        if ("PUT".equals(verb) && UPLOAD_CHUNK.matcher(uri).matches()) {
            return "upload.chunk";
        }
        if ("POST".equals(verb) && UPLOAD_COMPLETE.matcher(uri).matches()) {
            return "upload.complete";
        }
        if ("GET".equals(verb) && UPLOAD_STATUS.matcher(uri).matches()) {
            return "upload.status";
        }
        if ("DELETE".equals(verb) && UPLOAD_CANCEL.matcher(uri).matches()) {
            return "upload.cancel";
        }
        if ("DELETE".equals(verb) && FILE_BY_ID.matcher(uri).matches()) {
            return "delete";
        }
        return verb + " " + uri;
    }

    private static String exactAction(String verb, String uri) {
        return switch (uri) {
            case "/api/auth/verify" -> "GET".equals(verb) ? "auth.verify" : null;
            case "/api/auth/activate" -> "POST".equals(verb) ? "auth.activate" : null;
            case "/api/storage/roots" -> "GET".equals(verb) ? "roots.list" : null;
            case "/api/files" -> switch (verb) {
                case "GET" -> "files.list";
                case "DELETE" -> "delete";
                default -> null;
            };
            case "/api/files/dir" -> "GET".equals(verb) ? "dir.list" : null;
            case "/api/files/search" -> "GET".equals(verb) ? "search" : null;
            case "/api/files/download" -> "GET".equals(verb) ? "download" : null;
            case "/api/files/content" -> switch (verb) {
                case "GET" -> "content.read";
                case "PUT" -> "content.save";
                default -> null;
            };
            case "/api/files/upload" -> "POST".equals(verb) ? "upload" : null;
            case "/api/files/upload/init" -> "POST".equals(verb) ? "upload.init" : null;
            case "/api/files/rename" -> "POST".equals(verb) ? "rename" : null;
            case "/api/files/extract" -> "POST".equals(verb) ? "extract" : null;
            case "/api/files/copy" -> "POST".equals(verb) ? "copy" : null;
            case "/api/files/move" -> "POST".equals(verb) ? "move" : null;
            case "/api/terminal/exec" -> "POST".equals(verb) ? "terminal.exec" : null;
            case "/api/audit/logs" -> "GET".equals(verb) ? "audit.list" : null;
            default -> null;
        };
    }
}
