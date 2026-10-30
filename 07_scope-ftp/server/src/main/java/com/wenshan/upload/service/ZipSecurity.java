package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * zip 的安全预检：直接扫描中央目录，拒绝加密 entry 与 Unix 符号链接 entry。
 *
 * <p>JDK 的 {@link java.util.zip.ZipInputStream}/{@link java.util.zip.ZipFile} 不暴露 Unix mode，
 * 所以这里解析 EOCD + 中央目录记录：</p>
 * <ul>
 *   <li>中央目录 general purpose flag bit 0（偏移 8）= 加密；</li>
 *   <li>external file attributes（偏移 38）高 16 位为 Unix mode，类型位 0120000 = symlink。</li>
 * </ul>
 * <p>同时做最小完整性校验：必须存在 EOCD 且中央目录记录可完整解析，否则按「不是有效的 zip」拒绝
 * （JDK ZipInputStream 对随便一段字节会安静地返回「空 zip」，不能作为有效性依据）。</p>
 */
final class ZipSecurity {

    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long MAX_CENTRAL_DIR_BYTES = 32L * 1024 * 1024;
    private static final int EOCD_MIN_SIZE = 22;
    private static final int MAX_COMMENT_SIZE = 65_535;
    private static final int UNIX_SYMLINK_TYPE = 0xA000;
    private static final int TYPE_MASK = 0xF000;

    private ZipSecurity() {
    }

    static void checkArchive(Path zip) {
        try (RandomAccessFile raf = new RandomAccessFile(zip.toFile(), "r")) {
            long length = raf.length();
            if (length < EOCD_MIN_SIZE) {
                throw invalidZip();
            }
            int tailLength = (int) Math.min(length, EOCD_MIN_SIZE + MAX_COMMENT_SIZE);
            byte[] tail = new byte[tailLength];
            raf.seek(length - tailLength);
            raf.readFully(tail);
            int eocd = findEocd(tail);
            if (eocd < 0) {
                throw invalidZip();
            }
            int totalEntries = readUInt16(tail, eocd + 10);
            long centralSize = readUInt32(tail, eocd + 12);
            long centralOffset = readUInt32(tail, eocd + 16);
            if (totalEntries == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
                throw new BizException(ErrorCode.BAD_REQUEST, "暂不支持 zip64 压缩包，请分卷压缩后再解压");
            }
            if (centralOffset + centralSize > length) {
                throw invalidZip();
            }
            if (totalEntries == 0) {
                return;
            }
            if (centralSize > MAX_CENTRAL_DIR_BYTES) {
                throw new BizException(ErrorCode.BAD_REQUEST, "zip 中央目录过大，拒绝解压");
            }
            byte[] central = new byte[(int) centralSize];
            raf.seek(centralOffset);
            raf.readFully(central);

            int offset = 0;
            for (int i = 0; i < totalEntries; i++) {
                if (offset + 46 > central.length || readUInt32(central, offset) != CENTRAL_SIGNATURE) {
                    throw invalidZip();
                }
                int flags = readUInt16(central, offset + 8);
                int nameLength = readUInt16(central, offset + 28);
                int extraLength = readUInt16(central, offset + 30);
                int commentLength = readUInt16(central, offset + 32);
                long externalAttributes = readUInt32(central, offset + 38);
                int recordEnd = offset + 46 + nameLength + extraLength + commentLength;
                if (recordEnd > central.length) {
                    throw invalidZip();
                }
                int unixMode = (int) ((externalAttributes >>> 16) & 0xFFFF);
                if ((flags & 1) != 0) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "不支持加密 zip 文件");
                }
                if ((unixMode & TYPE_MASK) == UNIX_SYMLINK_TYPE) {
                    String name = new String(central, offset + 46, nameLength, StandardCharsets.UTF_8);
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "zip 中包含符号链接，拒绝解压: " + name);
                }
                offset = recordEnd;
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw invalidZip();
        }
    }

    private static BizException invalidZip() {
        return new BizException(ErrorCode.BAD_REQUEST, "不是有效的 zip 文件或文件已损坏");
    }

    /** 从文件尾部数据中找 EOCD（排除注释里的伪签名：要求注释长度与实际尾部长度一致）。 */
    private static int findEocd(byte[] tail) {
        for (int i = tail.length - EOCD_MIN_SIZE; i >= 0; i--) {
            if (readUInt32(tail, i) == EOCD_SIGNATURE) {
                int commentLength = readUInt16(tail, i + 20);
                if (i + EOCD_MIN_SIZE + commentLength == tail.length) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int readUInt16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long readUInt32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }
}
