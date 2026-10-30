package com.wenshan.upload.store;

/**
 * 元数据读写失败（磁盘 IO、JSON 损坏等），由全局异常处理映射为 50000。
 */
public class MetaStoreException extends RuntimeException {

    public MetaStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public MetaStoreException(String message) {
        super(message);
    }
}
