package io.github.codejinxz.yilian.upload.exception;

/**
 * 导入文件超过 5MB 上限。
 *
 * <p>单独定义异常而不是复用 {@code BusinessException}，是因为 {@code ApiCode} 目前没有 413 业务码； 由 {@link
 * io.github.codejinxz.yilian.upload.api.ImportExceptionHandler} 映射为 HTTP 413，
 * 业务码仍复用参数错误号段，避免为一次导入扩大公共错误码契约。
 */
public class ImportFileTooLargeException extends RuntimeException {

    private final long actualBytes;

    private final long maxBytes;

    /**
     * 创建超限异常。
     *
     * @param actualBytes 实际上传字节数
     * @param maxBytes 允许的最大字节数
     */
    public ImportFileTooLargeException(long actualBytes, long maxBytes) {
        super("导入文件超过大小上限");
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }

    /**
     * 实际上传字节数。
     *
     * @return 请求中的文件大小
     */
    public long actualBytes() {
        return actualBytes;
    }

    /**
     * 允许的最大字节数。
     *
     * @return 当前策略上限
     */
    public long maxBytes() {
        return maxBytes;
    }
}
