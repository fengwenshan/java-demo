package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.ContentSaveData;
import com.wenshan.upload.dto.FileContentData;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/**
 * 契约 3.18 在线编辑：读取 UTF-8 文本 + 原子覆盖保存（同路径、不新建/不改名）。
 *
 * <p>严格走 {@link StorageRootRegistry} + {@link StoragePaths}：路径只解析到请求指定的那个根内；
 * 目标必须是已存在的普通文件；大小上限（默认 2MiB）与严格 UTF-8 解码都不满足时返回 40000。
 * 保存使用同目录隐藏临时文件 + ATOMIC_MOVE 覆盖，尽力保留原文件权限；成功后同步该根 files.json
 * 中 storedPath 相同的记录（sha256/size），没有记录则不新建。</p>
 */
@Service
public class ContentEditService {

    private static final Logger log = LoggerFactory.getLogger(ContentEditService.class);
    private static final String TOO_LARGE_MESSAGE = "文件过大，不支持在线编辑";
    private static final String NOT_UTF8_MESSAGE = "二进制或非 UTF-8 文件不支持在线编辑";
    private static final String TEMP_PREFIX = ".upload-edit-";

    private final StorageRootRegistry rootRegistry;
    private final FileStorageService storageService;
    private final AppProperties properties;

    public ContentEditService(StorageRootRegistry rootRegistry,
                              FileStorageService storageService,
                              AppProperties properties) {
        this.rootRegistry = rootRegistry;
        this.storageService = storageService;
        this.properties = properties;
    }

    /** 读取文本：普通文件、≤max-edit-bytes、严格 UTF-8；返回当前内容 SHA-256 供保存时并发校验。 */
    public FileContentData read(String appKey, String rootParam, String path) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        Path file = resolveExistingFile(root, path);
        long size;
        byte[] bytes;
        try {
            size = Files.size(file);
            if (size > maxEditBytes()) {
                throw new BizException(ErrorCode.BAD_REQUEST, TOO_LARGE_MESSAGE);
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
        }
        String content = decodeStrictUtf8(bytes);
        String canonicalPath = storageService.relativize(root.paths(), file);
        return new FileContentData(canonicalPath, content, bytes.length,
                FileStorageService.sha256Hex(bytes), true);
    }

    /** 覆盖保存：content 必填（可为空串）；expectedSha256 可选并发校验；原子写 + 元数据同步。 */
    public ContentSaveData save(String appKey, String rootParam, String path, String content,
                                String expectedSha256) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        if (content == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 content");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxEditBytes()) {
            throw new BizException(ErrorCode.BAD_REQUEST, TOO_LARGE_MESSAGE);
        }
        Path file = resolveExistingFile(root, path);
        long currentSize;
        try {
            currentSize = Files.size(file);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String currentSha = storageService.sha256(file);
            if (!currentSha.equalsIgnoreCase(expectedSha256.trim())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文件已被修改，请重新加载后再保存");
            }
        }
        // 元数据写失败时尽量回滚内容：旧内容 ≤ max-edit-bytes 才缓存（在线编辑的对象通常都很小）
        byte[] oldBytes = null;
        if (currentSize <= maxEditBytes()) {
            try {
                oldBytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
            }
        }
        atomicOverwrite(file, bytes);
        String canonicalPath = storageService.relativize(root.paths(), file);
        String newSha = FileStorageService.sha256Hex(bytes);
        try {
            root.metaStore().updateContentByStoredPath(canonicalPath, bytes.length, newSha);
        } catch (RuntimeException e) {
            if (oldBytes != null) {
                try {
                    atomicOverwrite(file, oldBytes);
                } catch (RuntimeException rollbackError) {
                    log.warn("元数据同步失败后内容回滚失败: {} ({})", canonicalPath, rollbackError.toString());
                }
            }
            throw e;
        }
        return new ContentSaveData(canonicalPath, bytes.length, newSha);
    }

    private Path resolveExistingFile(StorageRoot root, String path) {
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 path 参数");
        }
        Path file = storageService.resolve(root.paths(), path);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标不是普通文件");
        }
        return file;
    }

    private long maxEditBytes() {
        return properties.getStorage().getMaxEditBytes();
    }

    private static String decodeStrictUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, NOT_UTF8_MESSAGE);
        }
    }

    /**
     * 同目录隐藏临时文件写入后 ATOMIC_MOVE 覆盖（不支持原子移动时退化为普通 move + REPLACE_EXISTING），
     * 尽力保留目标文件 POSIX 权限；任何失败都清理临时文件。
     */
    private void atomicOverwrite(Path target, byte[] bytes) {
        Path parent = target.getParent();
        Set<PosixFilePermission> permissions = null;
        try {
            permissions = Files.getPosixFilePermissions(target);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统：跳过权限保留
        }
        Path temp = null;
        try {
            for (int attempt = 0; attempt < 100 && temp == null; attempt++) {
                Path candidate = parent.resolve(TEMP_PREFIX + UUID.randomUUID() + ".tmp");
                try {
                    Files.write(candidate, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    temp = candidate;
                } catch (FileAlreadyExistsException e) {
                    // 随机名冲突，重试
                }
            }
            if (temp == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "保存失败");
            }
            if (permissions != null) {
                try {
                    Files.setPosixFilePermissions(temp, permissions);
                } catch (IOException | UnsupportedOperationException ignored) {
                    // 权限保留是尽力而为
                }
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "保存失败");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("清理在线编辑临时文件失败: {}", temp);
                }
            }
        }
    }
}
