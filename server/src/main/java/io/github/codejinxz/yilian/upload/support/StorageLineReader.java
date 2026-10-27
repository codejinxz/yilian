package io.github.codejinxz.yilian.upload.support;

import io.github.codejinxz.yilian.common.storage.ObjectStorage;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 把 {@link ObjectStorage#get(String, java.io.OutputStream)} 的输出适配成逐行读取。
 *
 * <p>{@code ObjectStorage} 只提供「写入调用方输出流」的读取方式，没有直接返回 {@code InputStream}； 这里用 64KB 管道 +
 * 虚拟线程把写侧与读侧解耦，读侧按行消费，内存占用只与单行长度有关， 不会把 5MB 文件整体读进内存。管道满时生产线程自然阻塞，形成背压。
 *
 * <p>失败语义：读侧消费抛出的异常原样向上传播；存储读失败在消费结束后包装成 {@link IOException} 抛出；任一方向异常都会关闭管道，避免工作线程泄漏。生产线程最多等待 30
 * 秒， 超时视为存储读取异常，批次会被标记 FAILED 并可 resume。
 */
public final class StorageLineReader {

    /** 管道缓冲区；远大于普通行长度，避免频繁切换线程。 */
    private static final int PIPE_BUFFER_BYTES = 64 * 1024;

    /** 生产线程退出等待上限，防止对象存储实现卡死时导入线程永久阻塞。 */
    private static final long PRODUCER_JOIN_TIMEOUT_MILLIS = 30_000L;

    private StorageLineReader() {}

    /**
     * 逐行读取对象内容。
     *
     * @param storage 对象存储
     * @param key 对象键
     * @param consumer 行消费者；抛出的运行时异常会终止读取并原样传播
     * @throws IOException 存储读取失败、管道异常或生产线程超时
     */
    public static void forEachLine(ObjectStorage storage, String key, Consumer<String> consumer)
            throws IOException {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(consumer, "consumer");

        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        try (PipedInputStream source = new PipedInputStream(PIPE_BUFFER_BYTES);
                PipedOutputStream sink = new PipedOutputStream(source)) {
            Thread producer =
                    Thread.ofVirtual()
                            .name("yilian-import-reader")
                            .start(
                                    () -> {
                                        try {
                                            storage.get(key, sink);
                                        } catch (Throwable failure) {
                                            readFailure.set(failure);
                                        } finally {
                                            closeQuietly(sink);
                                        }
                                    });

            try {
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
                String line;
                long lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                    consumer.accept(line);
                }
            } finally {
                // 消费异常时先关读侧，让生产线程的 write 尽快失败退出；正常结束时生产线程已关闭写侧。
                closeQuietly(source);
                joinProducer(producer);
            }

            if (producer.isAlive()) {
                throw new IOException("读取导入文件超时");
            }
            Throwable failure = readFailure.get();
            if (failure != null) {
                throw new IOException("读取导入文件失败", failure);
            }
        }
    }

    /**
     * 等待生产线程结束；不让 InterruptedException 覆盖消费侧异常，只恢复中断标记。
     *
     * @param producer 生产线程
     */
    private static void joinProducer(Thread producer) {
        try {
            producer.join(PRODUCER_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (producer.isAlive()) {
            producer.interrupt();
        }
    }

    /**
     * 关闭资源并忽略关闭异常；关闭失败不应覆盖真正的读取/校验错误。
     *
     * @param closeable 待关闭资源，允许为 null
     */
    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 管道关闭失败无需额外处理，真正的错误由 readFailure 或消费侧异常承载。
        }
    }
}
