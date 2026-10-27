package io.github.codejinxz.yilian.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 本地存储：流式读写、路径穿越拒绝与分片上传语义。 */
class LocalObjectStorageTest {

    @TempDir Path tempDir;

    private Path root;
    private LocalObjectStorage storage;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("root");
        storage = new LocalObjectStorage(root, root.resolve(".multipart"), "/media/");
    }

    @Test
    void putGetStatAndDeleteRoundTrip() throws IOException {
        byte[] payload = "hello 译练".getBytes(StandardCharsets.UTF_8);

        storage.put(
                "exercises/2026/a.txt",
                new ByteArrayInputStream(payload),
                payload.length,
                "text/plain");

        assertThat(storage.exists("exercises/2026/a.txt")).isTrue();
        StorageObject stat = storage.stat("exercises/2026/a.txt");
        assertThat(stat.size()).isEqualTo(payload.length);
        assertThat(stat.publicUrl()).isEqualTo("/media/exercises/2026/a.txt");

        ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
        StorageObject object = storage.get("exercises/2026/a.txt", downloaded);
        assertThat(downloaded.toByteArray()).isEqualTo(payload);
        assertThat(object.key()).isEqualTo("exercises/2026/a.txt");

        assertThat(storage.delete("exercises/2026/a.txt")).isTrue();
        assertThat(storage.exists("exercises/2026/a.txt")).isFalse();
        assertThat(storage.delete("exercises/2026/a.txt")).isFalse();
        assertThatThrownBy(() -> storage.get("exercises/2026/a.txt", new ByteArrayOutputStream()))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void rejectsLengthMismatchWithoutLeavingObject() {
        byte[] payload = "short".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(
                        () ->
                                storage.put(
                                        "length/mismatch.txt",
                                        new ByteArrayInputStream(payload),
                                        payload.length + 10,
                                        "text/plain"))
                .isInstanceOf(IOException.class);
        assertThat(storage.exists("length/mismatch.txt")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../evil.txt",
                "a/../../evil.txt",
                "..\\evil.txt",
                "/etc/passwd",
                "C:\\Windows\\evil.txt",
                "a//b.txt",
                "a/./b.txt",
                ".multipart/leak.txt",
                "a:b.txt",
                "a/ "
            })
    void rejectsKeysEscapingRootOrTemporaryDirectory(String key) {
        assertThatThrownBy(
                        () ->
                                storage.put(
                                        key,
                                        new ByteArrayInputStream(new byte[] {1}),
                                        1,
                                        "application/octet-stream"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tempDir.resolve("evil.txt")).doesNotExist();
        assertThat(root.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void presignReturnsPublicUrlOnlyWhenPrefixConfigured() throws IOException {
        storage.put("public/a.txt", new ByteArrayInputStream(new byte[] {1}), 1, "text/plain");

        assertThat(storage.presign("public/a.txt", Duration.ofMinutes(5)))
                .contains("/media/public/a.txt");

        LocalObjectStorage withoutPrefix =
                new LocalObjectStorage(
                        tempDir.resolve("plain"), tempDir.resolve("plain-temp"), null);
        assertThat(withoutPrefix.presign("a.txt", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void multipartUploadConcatenatesPartsInPartNumberOrder() throws IOException {
        byte[] first = "hello-".getBytes(StandardCharsets.UTF_8);
        byte[] second = "译练".getBytes(StandardCharsets.UTF_8);
        byte[] third = "-world".getBytes(StandardCharsets.UTF_8);

        MultipartUpload session = storage.initiateMultipart("multipart/result.txt", "text/plain");
        MultipartUpload.Part part1 = uploadPart(storage, session.uploadId(), 1, first);
        MultipartUpload.Part part2 = uploadPart(storage, session.uploadId(), 2, second);
        MultipartUpload.Part part3 = uploadPart(storage, session.uploadId(), 3, third);

        StorageObject completed =
                storage.completeMultipart(session.uploadId(), List.of(part3, part1, part2));

        assertThat(completed.size()).isEqualTo(first.length + second.length + third.length);
        ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
        storage.get("multipart/result.txt", downloaded);
        assertThat(downloaded.toByteArray()).isEqualTo(concat(first, second, third));
        assertThat(multipartSessionDirectory(session.uploadId())).doesNotExist();
    }

    @Test
    void multipartUploadRejectsTamperedEtag() throws IOException {
        byte[] payload = "part".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = storage.initiateMultipart("multipart/tampered.txt", null);
        storage.uploadPart(upload.uploadId(), 1, new ByteArrayInputStream(payload), payload.length);

        MultipartUpload.Part tampered =
                new MultipartUpload.Part(1, "not-the-real-etag", payload.length);

        assertThatThrownBy(() -> storage.completeMultipart(upload.uploadId(), List.of(tampered)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.exists("multipart/tampered.txt")).isFalse();
    }

    @Test
    void abortMultipartRemovesTemporaryPartsAndSession() throws IOException {
        byte[] payload = "part".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = storage.initiateMultipart("multipart/aborted.bin", null);
        MultipartUpload.Part part =
                storage.uploadPart(
                        upload.uploadId(), 1, new ByteArrayInputStream(payload), payload.length);
        Path sessionDirectory = multipartSessionDirectory(upload.uploadId());
        assertThat(sessionDirectory).exists();

        storage.abortMultipart(upload.uploadId());

        assertThat(sessionDirectory).doesNotExist();
        assertThat(storage.exists("multipart/aborted.bin")).isFalse();
        assertThatThrownBy(() -> storage.completeMultipart(upload.uploadId(), List.of(part)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentReuploadOfSamePartKeepsEtagConsistentWithFinalContent() throws Exception {
        byte[] first = "alpha-译练-".repeat(4096).getBytes(StandardCharsets.UTF_8);
        byte[] second = "beta-练习-".repeat(4096).getBytes(StandardCharsets.UTF_8);
        int uploaderCount = 2;
        int rounds = 4;
        int iterationsPerUploader = 60;
        ExecutorService executor = Executors.newFixedThreadPool(uploaderCount);
        try {
            for (int round = 0; round < rounds; round++) {
                MultipartUpload upload =
                        storage.initiateMultipart("multipart/concurrent-" + round + ".bin", null);
                MultipartUpload.Part[] results = new MultipartUpload.Part[uploaderCount];
                CyclicBarrier barrier = new CyclicBarrier(uploaderCount);
                List<Future<?>> futures = new ArrayList<>();
                for (int uploader = 0; uploader < uploaderCount; uploader++) {
                    byte[] payload = uploader == 0 ? first : second;
                    int slot = uploader;
                    futures.add(
                            executor.submit(
                                    () -> {
                                        for (int iteration = 0;
                                                iteration < iterationsPerUploader;
                                                iteration++) {
                                            barrier.await(10, TimeUnit.SECONDS);
                                            results[slot] =
                                                    storage.uploadPart(
                                                            upload.uploadId(),
                                                            1,
                                                            new ByteArrayInputStream(payload),
                                                            payload.length);
                                        }
                                        return null;
                                    }));
                }
                for (Future<?> future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }

                // 两个线程各自返回的 etag 只有一个与最终登记一致；无论最终留下哪一份，
                // 合并后的文件内容都必须能重算出该 etag。
                MultipartUpload.Part accepted = null;
                for (MultipartUpload.Part candidate : results) {
                    try {
                        storage.completeMultipart(upload.uploadId(), List.of(candidate));
                        accepted = candidate;
                        break;
                    } catch (IllegalArgumentException overwritten) {
                        // 该线程的分片已被并发重传覆盖；合并失败会保留会话，可换另一个 etag 重试。
                    }
                }
                assertThat(accepted).as("至少有一个分片 etag 与最终登记一致").isNotNull();
                ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
                storage.get(upload.key(), downloaded);
                assertThat(sha256Hex(downloaded.toByteArray()))
                        .as("完成后的对象内容必须与调用方确认的 etag 一致")
                        .isEqualTo(accepted.etag());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void uploadPartAfterCompleteIsRejectedAsClosedSession() throws IOException {
        byte[] payload = "completed".getBytes(StandardCharsets.UTF_8);
        byte[] late = "late".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = storage.initiateMultipart("multipart/closed.bin", null);
        MultipartUpload.Part part = uploadPart(storage, upload.uploadId(), 1, payload);
        storage.completeMultipart(upload.uploadId(), List.of(part));

        assertThatThrownBy(() -> uploadPart(storage, upload.uploadId(), 1, late))
                .as("完成后的迟到分片必须得到明确的业务异常，而不是底层 NoSuchFileException")
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("已结束");
        assertThatThrownBy(() -> storage.completeMultipart(upload.uploadId(), List.of(part)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(multipartSessionDirectory(upload.uploadId())).doesNotExist();

        ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
        storage.get("multipart/closed.bin", downloaded);
        assertThat(downloaded.toByteArray()).isEqualTo(payload);
    }

    @Test
    void completeRejectsPartFileWhoseContentNoLongerMatchesRecordedEtag() throws IOException {
        byte[] payload = "original".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = storage.initiateMultipart("multipart/tampered-file.bin", null);
        MultipartUpload.Part part = uploadPart(storage, upload.uploadId(), 1, payload);
        Path partFile = multipartSessionDirectory(upload.uploadId()).resolve("part-1.bin");
        Files.write(partFile, "replaced".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> storage.completeMultipart(upload.uploadId(), List.of(part)))
                .as("complete 必须按文件重算 SHA-256，不能只信内存 etag")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("etag");
        assertThat(storage.exists("multipart/tampered-file.bin")).isFalse();

        // 校验失败不应销毁会话：恢复分片内容后仍可重试完成。
        Files.write(partFile, payload);
        storage.completeMultipart(upload.uploadId(), List.of(part));
        assertThat(storage.exists("multipart/tampered-file.bin")).isTrue();
    }

    /**
     * 上传一个分片并返回服务端记录。
     *
     * @param target 存储实现
     * @param uploadId 会话标识
     * @param partNumber 分片号
     * @param payload 分片内容
     * @return 分片记录
     * @throws IOException 上传失败时抛出
     */
    private static MultipartUpload.Part uploadPart(
            LocalObjectStorage target, String uploadId, int partNumber, byte[] payload)
            throws IOException {
        return target.uploadPart(
                uploadId, partNumber, new ByteArrayInputStream(payload), payload.length);
    }

    /**
     * 分片会话临时目录，用于断言清理结果。
     *
     * @param uploadId 会话标识
     * @return 临时目录路径
     */
    private Path multipartSessionDirectory(String uploadId) {
        return root.resolve(".multipart").resolve("multipart").resolve(uploadId);
    }

    /**
     * 计算字节数组的 SHA-256 十六进制摘要，与存储实现返回的 etag 口径一致。
     *
     * @param payload 待摘要内容
     * @return 小写十六进制摘要
     */
    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /**
     * 顺序拼接字节数组。
     *
     * @param chunks 字节数组片段
     * @return 拼接结果
     */
    private static byte[] concat(byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }
}
