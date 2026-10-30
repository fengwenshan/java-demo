package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 本地磁盘文件存储（FTP 式）：文件以原始文件名直接写入用户指定目录，重名时追加 {@code " (n)"}。
 *
 * <p>目录与文件路径都走 {@link StoragePaths} 的严格校验，绝不读写到根目录之外；
 * 写盘用 {@code CREATE_NEW} 原子占位，天然避免重名覆盖。</p>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final int BUFFER_SIZE = 64 * 1024;
    /** 同名文件最多尝试的序号（超过视为 50000）。 */
    static final int MAX_COLLISION_ATTEMPTS = 1000;

    private final long maxFileSize;

    public FileStorageService(AppProperties properties) {
        this.maxFileSize = properties.getStorage().getMaxFileSize();
    }

    /**
     * 把上传内容流式写入「相对 root 的目录」下的目标文件，同时计算 SHA-256；
     * 超过大小上限、IO 失败或内容为空时删除半成品。
     *
     * @param rootPaths   请求指定的存储根的路径校验器（只能解析到该根内）
     * @param relativeDir 相对 root 的目录（空/缺省表示 root 本身）；目录必须已存在
     * @param fileName    已清洗过的落盘文件名（不含路径分隔符）
     * @throws BizException 40000 目录非法；40400 目录不存在；50000 同名尝试耗尽
     */
    public StoreResult store(StoragePaths rootPaths, String relativeDir, String fileName, MultipartFile file) {
        return store(rootPaths, relativeDir, fileName, file::getInputStream);
    }

    /**
     * 通用流式落盘（分片上传 complete 时拼接分片使用）：从 supplier 读取内容写入目标目录下的目标文件，
     * 同时计算 SHA-256。目录校验、重名规则、大小上限与空文件规则与 multipart 上传完全一致。
     *
     * @param supplier 内容来源（每次调用打开一个新的输入流；store 只调用一次）
     */
    public StoreResult store(StoragePaths rootPaths, String relativeDir, String fileName,
                             InputStreamSupplier supplier) {
        Path directory = resolveTargetDirectory(rootPaths, relativeDir);

        Path target = null;
        for (int attempt = 0; attempt < MAX_COLLISION_ATTEMPTS; attempt++) {
            String candidateName = attempt == 0 ? fileName : nameWithCollisionSuffix(fileName, attempt);
            Path candidate = directory.resolve(candidateName);
            // 防御：目录内容可能在并发中被替换为符号链接，写前重走统一校验
            rootPaths.resolveStoredPath(relativize(rootPaths, candidate));
            try {
                Files.createFile(candidate);
                target = candidate;
                break;
            } catch (FileAlreadyExistsException e) {
                // 重名，追加序号继续尝试
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "文件创建失败");
            }
        }
        if (target == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "同名文件过多，无法生成唯一文件名: " + fileName);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        long totalBytes = 0;
        try {
            try (InputStream rawInput = supplier.open();
                 DigestInputStream input = new DigestInputStream(new BufferedInputStream(rawInput), digest);
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                         target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > maxFileSize) {
                        throw new BizException(ErrorCode.FILE_TOO_LARGE);
                    }
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            deleteQuietly(target);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件写入失败");
        } catch (BizException e) {
            deleteQuietly(target);
            throw e;
        }
        if (totalBytes == 0) {
            deleteQuietly(target);
            throw new BizException(ErrorCode.BAD_REQUEST, "空文件");
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        return new StoreResult(relativize(rootPaths, target), totalBytes, sha256);
    }

    /**
     * 解析并校验「相对 root 的目标目录」：非法/越界/内部目录 → 40000；不存在 → 40400；不是目录 → 40000。
     */
    public Path resolveTargetDirectory(StoragePaths rootPaths, String relativeDir) {
        Path directory = rootPaths.resolveRootDir(relativeDir);
        if (!Files.exists(directory)) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标目录不存在");
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标不是目录");
        }
        return directory;
    }

    /** 解析相对 root 的路径为绝对路径（非法路径抛 40000）。 */
    public Path resolve(StoragePaths rootPaths, String relativePath) {
        return rootPaths.resolveStoredPath(relativePath);
    }

    public boolean exists(StoragePaths rootPaths, String relativePath) {
        return Files.isRegularFile(resolve(rootPaths, relativePath));
    }

    /** 删除磁盘文件（best effort）；非法路径不会触碰根目录之外。 */
    public void delete(StoragePaths rootPaths, String relativePath) {
        try {
            Files.deleteIfExists(resolve(rootPaths, relativePath));
        } catch (IOException | RuntimeException e) {
            log.warn("删除磁盘文件失败: {} ({})", relativePath, e.getMessage());
        }
    }

    /** 计算文件内容的 SHA-256（下载路径接口使用；文件大小受 maxFileSize 约束）。 */
    public String sha256(Path path) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        try (InputStream input = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(path)), digest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (input.read(buffer) != -1) {
                // 读取即更新 digest
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 相对 root 的路径字符串，统一 "/" 分隔。 */
    public String relativize(StoragePaths rootPaths, Path path) {
        Path relative = rootPaths.root().relativize(path.toAbsolutePath().normalize());
        return relative.toString().replace(File.separatorChar, '/');
    }

    /** {@code report.pdf} → {@code report (1).pdf}；无扩展名或点开头时直接追加 {@code " (n)"}。 */
    static String nameWithCollisionSuffix(String fileName, int attempt) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return fileName + " (" + attempt + ")";
        }
        return fileName.substring(0, dot) + " (" + attempt + ")" + fileName.substring(dot);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理临时文件失败: {}", path);
        }
    }

    public static String sha256Hex(byte[] data) {
        return SignatureService.sha256Hex(data);
    }

    /** 内容来源：返回一个新的输入流（由 store 负责关闭）。 */
    @FunctionalInterface
    public interface InputStreamSupplier {
        InputStream open() throws IOException;
    }

    /** 存储结果：relativePath 相对 root（统一 "/"）。 */
    public record StoreResult(String relativePath, long size, String sha256) {
    }
}
