package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.dto.CreateEntryData;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 在存储根目录内新建空文件或文件夹。
 *
 * <p>父目录与目标路径都经 {@link StoragePaths#resolveRootDir(String)} /
 * {@link StoragePaths#resolveStoredPath(String)} 严格校验：拒绝绝对路径、{@code ..}、符号链接、
 * 内部元数据目录与越界；目标已存在时返回 40000，绝不覆盖。新文件为空文件，不写 files.json。</p>
 */
@Service
public class EntryCreateService {

    private static final Logger log = LoggerFactory.getLogger(EntryCreateService.class);
    private static final int MAX_NAME_LENGTH = 255;

    private final StorageRootRegistry rootRegistry;
    private final FileStorageService storageService;

    public EntryCreateService(StorageRootRegistry rootRegistry, FileStorageService storageService) {
        this.rootRegistry = rootRegistry;
        this.storageService = storageService;
    }

    /** 新建文件或目录；{@code type} 不区分大小写，只接受 file / directory。 */
    public CreateEntryData create(String appKey, String rootParam, String parent, String name, String type) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        boolean directory = parseType(type);
        String safeName = validateName(name);

        String parentParam = parent == null ? "" : parent.trim();
        Path parentDir = root.paths().resolveRootDir(parentParam);
        if (!Files.exists(parentDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标目录不存在");
        }
        if (!Files.isDirectory(parentDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标路径不是目录");
        }

        Path target = parentDir.resolve(safeName).normalize();
        if (!parentDir.equals(target.getParent())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称不能包含路径分隔符");
        }
        String parentRelative = storageService.relativize(root.paths(), parentDir);
        String relativePath = parentRelative.isEmpty() ? safeName : parentRelative + "/" + safeName;
        // 目标父目录无 symlink / 非内部目录 / 不越界；新路径本身尚不存在，但也要走统一校验。
        root.paths().resolveStoredPath(relativePath);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "同名文件或目录已存在");
        }

        try {
            if (directory) {
                Files.createDirectory(target);
            } else {
                Files.createFile(target);
            }
        } catch (FileAlreadyExistsException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "同名文件或目录已存在");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "创建失败: " + e.getMessage());
        }
        log.info("新建{}: {}:{}（appKey={}）", directory ? "文件夹" : "文件",
                root.id(), relativePath, appKey);
        return new CreateEntryData(root.id(), relativePath,
                directory ? "directory" : "file", safeName);
    }

    private static boolean parseType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "file" -> false;
            case "directory" -> true;
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "type 必须是 file 或 directory");
        };
    }

    /** 单段名称：trim 后 1~255 字符，不含分隔符/控制字符，不能是 . / .. 或内部元数据目录名。 */
    static String validateName(String raw) {
        String trimmed = raw == null ? null : raw.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称不能为空");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称最长 255 字符");
        }
        if (trimmed.indexOf('\0') >= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称包含非法字符");
        }
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称不能包含路径分隔符");
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (current < 0x20 || current == 0x7F) {
                throw new BizException(ErrorCode.BAD_REQUEST, "名称包含控制字符");
            }
        }
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称不能是 . 或 ..");
        }
        if (StoragePaths.INTERNAL_META_DIR.equalsIgnoreCase(trimmed)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不允许使用内部元数据目录名");
        }
        return trimmed;
    }
}
