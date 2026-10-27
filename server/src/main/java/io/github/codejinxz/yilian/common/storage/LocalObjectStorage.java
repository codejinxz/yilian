package io.github.codejinxz.yilian.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地文件系统实现，供开发、单机部署与测试使用。
 *
 * <p><strong>安全边界</strong>：所有对象键先按统一规则归一化，再拼到根目录下并再次校验是否仍在根目录内； 已存在的目标文件还会用 {@code toRealPath()}
 * 检查符号链接指向，防止通过软链接读出根目录之外的文件。 临时目录（分片与会话文件）位于根目录内时，对象键不允许指向该目录，避免把上传中间态当成对象读走。
 *
 * <p><strong>写入语义</strong>：先写同文件系统临时文件，再原子移动到目标位置，避免读到写了一半的对象；
 * 文件系统不支持原子移动时退化为覆盖移动。下载使用流复制，不把整个对象读进内存。
 *
 * <p><strong>分片上传</strong>：分片会话保存在进程内 {@link ConcurrentHashMap}，分片内容落在临时目录。
 * 重启后会话与未完成分片不可恢复，这是本地实现的已知取舍；生产环境应切换到云对象存储的持久化分片。
 *
 * <p><strong>分片并发一致性</strong>：每个会话持有一把公平读写锁，{@code uploadPart} 持读锁、{@code completeMultipart}/{@code
 * abortMultipart} 持写锁，保证合并期间没有分片仍在写入。 同一 {@code uploadId + partNumber} 另有一把分片锁，把「移动文件」与「登记
 * etag」放在同一临界区，避免并发重传时文件内容与内存 etag 错位。 完成上传时还会按分片文件重新计算 SHA-256 并与登记值比对，任何不一致都会拒绝完成。
 * 会话一旦完成即标记关闭；迟到的 {@code uploadPart} 抛出 {@link IllegalStateException} 业务错误，而不是底层文件不存在异常。
 *
 * <p>5MB 等业务限制由调用方在进入本类之前校验；本类只负责存储语义，不重复实现产品规则。
 */
public final class LocalObjectStorage implements ObjectStorage {

    /** 临时目录下保存分片与会话文件的子目录，避免与对象键空间混在一起。 */
    private static final String MULTIPART_SUBDIRECTORY = "multipart";

    /** 分片号上限，与主流云对象存储保持一致。 */
    private static final int MAX_PART_NUMBER = 10_000;

    /** uploadId 允许的字符集，防止被拼进路径时越界。 */
    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    /** 已完成会话标识的保留上限：用于把迟到请求识别为「会话已结束」，同时避免无限增长。 */
    private static final int MAX_TRACKED_CLOSED_UPLOADS = 256;

    /** Windows 禁止的文件名字符；冒号同时拦截盘符与 NTFS 数据流。 */
    private static final String FORBIDDEN_KEY_CHARACTERS = "<>:\"|?*";

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorage.class);

    private final Path root;
    private final Path tempDir;
    private final Path multipartDir;
    private final String publicUrlPrefix;

    /** 进程内分片会话；本地实现不持久化，重启后丢弃。 */
    private final ConcurrentMap<String, UploadSession> uploads = new ConcurrentHashMap<>();

    /** 已完成会话标识；有界保留，使迟到分片得到明确业务错误而不是「会话不存在」。 */
    private final ConcurrentMap<String, Boolean> closedUploads = new ConcurrentHashMap<>();

    /** 已完成会话标识的写入顺序，用于从 {@link #closedUploads} 中淘汰最旧记录。 */
    private final Queue<String> closedUploadOrder = new ConcurrentLinkedQueue<>();

    /**
     * 创建本地对象存储。
     *
     * @param root 对象根目录，不能为 null；不存在时在首次写入时创建
     * @param tempDir 临时目录，不能为 null；不得等于或包含根目录
     * @param publicUrlPrefix 公开访问前缀，允许为 null 或空串（表示不生成公开 URL）
     * @throws IllegalArgumentException 根目录为空、临时目录等于或包含根目录时抛出
     */
    public LocalObjectStorage(Path root, Path tempDir, String publicUrlPrefix) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(tempDir, "tempDir");
        this.root = root.toAbsolutePath().normalize();
        this.tempDir = tempDir.toAbsolutePath().normalize();
        if (this.tempDir.equals(this.root) || this.root.startsWith(this.tempDir)) {
            throw new IllegalArgumentException("临时目录不能等于或包含对象根目录");
        }
        this.multipartDir = this.tempDir.resolve(MULTIPART_SUBDIRECTORY);
        this.publicUrlPrefix = normalizePrefix(publicUrlPrefix);
    }

    /**
     * 流式写入对象：先写临时文件并校验声明长度，再原子移动到目标路径。
     *
     * @param key 对象键
     * @param content 内容流，方法不负责关闭
     * @param contentLength 声明长度，未知时传 -1
     * @param contentType MIME 类型；本地实现不持久化，读取时按扩展名探测
     * @throws IOException 读写失败或实际长度与声明不一致时抛出
     */
    @Override
    public void put(String key, InputStream content, long contentLength, String contentType)
            throws IOException {
        Objects.requireNonNull(content, "content");
        Path target = resolveObject(key);
        assertNoSymlinkEscape(target);
        Files.createDirectories(target.getParent());
        Files.createDirectories(tempDir);
        Path staged = Files.createTempFile(tempDir, "put-", ".part");
        try {
            long written;
            try (OutputStream out = Files.newOutputStream(staged)) {
                written = content.transferTo(out);
            }
            if (contentLength >= 0 && written != contentLength) {
                throw new IOException(
                        "写入长度与声明不一致: expected=" + contentLength + ", actual=" + written);
            }
            moveReplacing(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * 流式读取对象到调用方输出流，返回元数据；不关闭调用方的流。
     *
     * @param key 对象键
     * @param target 目标输出流
     * @return 对象元数据
     * @throws NoSuchFileException 对象不存在时抛出
     * @throws IOException 读取失败或键经符号链接越界时抛出
     */
    @Override
    public StorageObject get(String key, OutputStream target) throws IOException {
        Objects.requireNonNull(target, "target");
        Path file = resolveObject(key);
        assertNoSymlinkEscape(file);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(key);
        }
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(target);
        }
        return toStorageObject(key, file);
    }

    /**
     * 删除对象并清理空目录；对象不存在或键经符号链接越界时返回 false。
     *
     * @param key 对象键
     * @return 是否确实删除了对象
     * @throws IllegalArgumentException 键非法时抛出
     */
    @Override
    public boolean delete(String key) {
        Path file = resolveObject(key);
        try {
            assertNoSymlinkEscape(file);
            if (!Files.isRegularFile(file)) {
                return false;
            }
            Files.delete(file);
            cleanupEmptyParents(file.getParent());
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("删除对象失败: " + key, e);
        }
    }

    /**
     * 判断对象是否存在；键非法时抛异常，键合法但符号链接越界时按不存在处理。
     *
     * @param key 对象键
     * @return 存在且为普通文件时返回 true
     * @throws IllegalArgumentException 键非法时抛出
     */
    @Override
    public boolean exists(String key) {
        Path file = resolveObject(key);
        try {
            assertNoSymlinkEscape(file);
            return Files.isRegularFile(file);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 本地实现没有签名机制，只在配置了公开访问前缀时返回拼接好的公开 URL。
     *
     * <p>有效期被刻意忽略：前缀一旦配置就意味着该目录可被公开读取，调用方必须自行保证只对 非敏感对象使用；需要鉴权的下载请走 {@link #get}。
     *
     * @param key 对象键
     * @param validity 有效期，必须为正
     * @return 公开 URL，未配置前缀时为空
     * @throws IllegalArgumentException 键非法或有效期非正时抛出
     */
    @Override
    public Optional<String> presign(String key, Duration validity) {
        resolveObject(key);
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("预签名有效期必须为正数");
        }
        return Optional.ofNullable(publicUrl(key));
    }

    /**
     * 初始化分片上传会话；会话只存在于当前进程，重启后失效。
     *
     * @param key 目标对象键
     * @param contentType MIME 类型，允许为 null
     * @return 新建的上传会话
     * @throws IOException 无法创建会话目录时抛出
     */
    @Override
    public MultipartUpload initiateMultipart(String key, String contentType) throws IOException {
        Path target = resolveObject(key);
        assertNoSymlinkEscape(target);
        String uploadId = UUID.randomUUID().toString();
        Path sessionDir = multipartDir.resolve(uploadId);
        Files.createDirectories(sessionDir);
        UploadSession session =
                new UploadSession(
                        uploadId,
                        key,
                        target,
                        contentType,
                        Instant.now(),
                        sessionDir,
                        new ConcurrentSkipListMap<>());
        uploads.put(uploadId, session);
        return session.toUpload();
    }

    /**
     * 上传分片：流式写入临时分片文件并计算 SHA-256 作为 etag，不把分片读进内存。
     *
     * <p>整个写入持会话读锁，同一 {@code uploadId + partNumber} 另有分片锁，因此文件移动与 etag 登记对其他重传者原子可见；
     * 会话已结束时在触碰临时目录之前抛出 {@link IllegalStateException}。
     *
     * @param uploadId 上传会话标识
     * @param partNumber 分片号，从 1 开始
     * @param content 分片内容流，方法不负责关闭
     * @param contentLength 声明长度，未知时传 -1
     * @return 分片记录
     * @throws IOException 读写失败或长度不一致时抛出
     * @throws IllegalStateException 会话已经完成，或本次上传等待期间会话被完成/中止时抛出
     */
    @Override
    public MultipartUpload.Part uploadPart(
            String uploadId, int partNumber, InputStream content, long contentLength)
            throws IOException {
        Objects.requireNonNull(content, "content");
        if (partNumber < 1 || partNumber > MAX_PART_NUMBER) {
            throw new IllegalArgumentException("分片号必须在 1 到 " + MAX_PART_NUMBER + " 之间");
        }
        UploadSession session = requireSession(uploadId);
        Lock lifecycle = session.lifecycleLock.readLock();
        lifecycle.lock();
        try {
            assertSessionOpen(session);
            Lock partLock =
                    session.partLocks.computeIfAbsent(partNumber, ignored -> new ReentrantLock());
            partLock.lock();
            try {
                // 写锁只在所有读锁释放后生效，这里是防御性复查，避免关闭标记与分片锁之间的竞态。
                assertSessionOpen(session);
                Path partFile = session.directory.resolve(partFileName(partNumber));
                Path staged = Files.createTempFile(session.directory, "part-", ".tmp");
                try {
                    MessageDigest digest = sha256();
                    long written;
                    try (DigestOutputStream out =
                            new DigestOutputStream(Files.newOutputStream(staged), digest)) {
                        written = content.transferTo(out);
                    }
                    if (contentLength >= 0 && written != contentLength) {
                        throw new IOException(
                                "分片长度与声明不一致: expected=" + contentLength + ", actual=" + written);
                    }
                    // 移动与登记必须同处临界区：否则并发重传可能留下「文件是新内容、etag 是旧值」。
                    moveReplacing(staged, partFile);
                    String etag = HexFormat.of().formatHex(digest.digest());
                    MultipartUpload.Part part = new MultipartUpload.Part(partNumber, etag, written);
                    session.parts.put(partNumber, part);
                    return part;
                } finally {
                    Files.deleteIfExists(staged);
                }
            } finally {
                partLock.unlock();
            }
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * 按分片号升序合并分片，校验调用方给出的 etag 后原子落盘。
     *
     * <p>进入临界区后先标记会话关闭，再按调用方 etag 与内存记录比对，然后逐片从文件重算 SHA-256 并写入合并文件；
     * 两道校验都通过才原子替换目标对象。失败时会话恢复开放，可重试或中止。 合并与 {@link #uploadPart} 通过会话读写锁互斥，因此不会把仍在写入的分片合并进去。
     *
     * @param uploadId 上传会话标识
     * @param parts 调用方确认的分片列表，不能为空
     * @return 合并后的对象元数据
     * @throws IOException 合并失败、分片文件缺失或文件内容与登记 etag 不一致时抛出；失败时会话保留，可重试或中止
     * @throws IllegalArgumentException 分片缺失、etag 不一致、列表为空或会话不存在/已中止时抛出
     * @throws IllegalStateException 会话已经完成时抛出
     */
    @Override
    public StorageObject completeMultipart(String uploadId, List<MultipartUpload.Part> parts)
            throws IOException {
        UploadSession session = requireSession(uploadId);
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("完成分片上传至少要有一个分片");
        }
        List<MultipartUpload.Part> ordered =
                parts.stream()
                        .sorted(Comparator.comparingInt(MultipartUpload.Part::partNumber))
                        .toList();
        Files.createDirectories(tempDir);
        Path staged = Files.createTempFile(tempDir, "complete-", ".part");
        try {
            Lock lifecycle = session.lifecycleLock.writeLock();
            lifecycle.lock();
            try {
                assertSessionOpen(session);
                // 先关闭再合并：迟到 uploadPart 会被读锁挡住，拿到锁后看到关闭标记即抛业务异常。
                session.closed = true;
                try {
                    verifyParts(session, ordered);
                    try (OutputStream out = Files.newOutputStream(staged)) {
                        for (MultipartUpload.Part part : ordered) {
                            Path partFile =
                                    session.directory.resolve(partFileName(part.partNumber()));
                            if (!Files.isRegularFile(partFile)) {
                                throw new IOException("分片文件缺失: " + part.partNumber());
                            }
                            copyAndVerify(partFile, out, part);
                        }
                    }
                    assertNoSymlinkEscape(session.target);
                    Files.createDirectories(session.target.getParent());
                    moveReplacing(staged, session.target);
                } catch (IOException | RuntimeException e) {
                    // 失败不丢会话：恢复开放后调用方可以重传分片并重试 complete。
                    if (uploads.get(uploadId) == session) {
                        session.closed = false;
                    }
                    throw e;
                }
                // 先登记已结束再移除会话，保证并发 requireSession 不会落入「不存在且未登记」的真空。
                rememberClosed(uploadId);
                uploads.remove(uploadId, session);
            } finally {
                lifecycle.unlock();
            }
            deleteRecursively(session.directory);
            return toStorageObject(session.key, session.target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * 中止上传并删除临时分片；会话不存在时幂等返回。
     *
     * <p>删除临时目录前取会话写锁并标记关闭，避免与仍在写入的分片或正在合并的 complete 交叉删除文件。
     *
     * @param uploadId 上传会话标识
     * @throws IllegalArgumentException uploadId 格式非法时抛出
     */
    @Override
    public void abortMultipart(String uploadId) {
        validateUploadId(uploadId);
        UploadSession session = uploads.remove(uploadId);
        if (session == null) {
            return;
        }
        Lock lifecycle = session.lifecycleLock.writeLock();
        lifecycle.lock();
        try {
            session.closed = true;
            deleteRecursively(session.directory);
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * 读取对象元数据，不读取内容。
     *
     * @param key 对象键
     * @return 对象元数据
     * @throws NoSuchFileException 对象不存在时抛出
     * @throws IOException 读取属性失败时抛出
     */
    @Override
    public StorageObject stat(String key) throws IOException {
        Path file = resolveObject(key);
        assertNoSymlinkEscape(file);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(key);
        }
        return toStorageObject(key, file);
    }

    /**
     * 把对象键归一化为安全相对路径并解析到根目录下。
     *
     * @param key 原始对象键
     * @return 根目录下的绝对路径
     * @throws IllegalArgumentException 键为空、含越界段、绝对路径或指向临时目录时抛出
     */
    private Path resolveObject(String key) {
        String normalized = normalizeKey(key);
        Path candidate = root.resolve(normalized).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("对象键越出存储根目录: " + key);
        }
        if (candidate.startsWith(tempDir)) {
            throw new IllegalArgumentException("对象键不能指向存储临时目录: " + key);
        }
        return candidate;
    }

    /**
     * 归一化对象键：统一分隔符，拒绝空段、{@code .}/{@code ..}、绝对路径、盘符、控制字符与 Windows 禁止字符，并拒绝以点或空格结尾的段（Windows
     * 会静默去掉，导致实际路径与校验不一致）。
     *
     * @param key 原始对象键
     * @return 以正斜杠连接的相对路径
     * @throws IllegalArgumentException 任一段非法时抛出
     */
    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        String replaced = key.replace('\\', '/');
        if (replaced.startsWith("/") || replaced.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("对象键必须是相对路径: " + key);
        }
        String[] segments = replaced.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("对象键不能包含空段、. 或 ..: " + key);
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IllegalArgumentException("对象键的路径段不能以点或空格结尾: " + key);
            }
            for (int i = 0; i < segment.length(); i++) {
                char current = segment.charAt(i);
                if (current < 0x20
                        || current == 0x7f
                        || FORBIDDEN_KEY_CHARACTERS.indexOf(current) >= 0) {
                    throw new IllegalArgumentException("对象键包含非法字符: " + key);
                }
            }
        }
        return String.join("/", segments);
    }

    /**
     * 检查已存在文件解析符号链接后的真实位置是否仍在根目录内。
     *
     * @param candidate 根目录下的候选路径
     * @throws IOException 真实路径越出根目录或进入临时目录时抛出
     */
    private void assertNoSymlinkEscape(Path candidate) throws IOException {
        Path realRoot = Files.exists(root) ? root.toRealPath() : root;
        Path realTemp = Files.exists(tempDir) ? tempDir.toRealPath() : null;
        Path current = candidate;
        while (current != null && current.startsWith(root)) {
            if (Files.exists(current)) {
                Path real = current.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw new IOException("对象键经符号链接越出存储根目录");
                }
                if (realTemp != null && real.startsWith(realTemp)) {
                    throw new IOException("对象键不能指向存储临时目录");
                }
            }
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
    }

    /**
     * 原子移动临时文件到目标；文件系统不支持时退化为覆盖移动。
     *
     * @param source 临时文件
     * @param target 目标路径
     * @throws IOException 移动失败时抛出
     */
    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 读取文件属性并组装元数据；content type 探测失败时留空而不是让整个读取失败。
     *
     * @param key 对象键
     * @param file 对象文件
     * @return 对象元数据
     * @throws IOException 读取属性失败时抛出
     */
    private StorageObject toStorageObject(String key, Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return new StorageObject(
                key,
                attributes.size(),
                attributes.lastModifiedTime().toInstant(),
                probeContentType(file),
                null,
                publicUrl(key));
    }

    /**
     * 按扩展名探测 MIME 类型，失败返回 null。
     *
     * @param file 对象文件
     * @return MIME 类型或 null
     */
    private static String probeContentType(Path file) {
        try {
            return Files.probeContentType(file);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 拼接公开访问 URL 并对每个路径段做 URL 编码。
     *
     * @param key 对象键
     * @return 公开 URL，未配置前缀时返回 null
     */
    private String publicUrl(String key) {
        if (publicUrlPrefix.isEmpty()) {
            return null;
        }
        StringBuilder url = new StringBuilder(publicUrlPrefix);
        for (String segment : key.split("/")) {
            url.append('/')
                    .append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return url.toString();
    }

    /**
     * 去掉公开前缀末尾的斜杠，null 或空白归一为空串。
     *
     * @param prefix 原始前缀
     * @return 归一化前缀
     */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 获取分片会话；已完成的会话抛 {@link IllegalStateException}，从未存在或已中止的会话抛 {@link IllegalArgumentException}。
     *
     * @param uploadId 上传会话标识
     * @return 会话
     * @throws IllegalStateException 会话已经完成时抛出
     * @throws IllegalArgumentException uploadId 格式非法或会话不存在时抛出
     */
    private UploadSession requireSession(String uploadId) {
        validateUploadId(uploadId);
        UploadSession session = uploads.get(uploadId);
        if (session != null) {
            return session;
        }
        if (closedUploads.containsKey(uploadId)) {
            throw new IllegalStateException("分片上传会话已结束，拒绝迟到请求: " + uploadId);
        }
        throw new IllegalArgumentException("分片上传会话不存在或已结束: " + uploadId);
    }

    /**
     * 校验会话仍处于开放状态；必须在持锁后调用，避免读到过期的关闭标记。
     *
     * @param session 上传会话
     * @throws IllegalStateException 会话已完成或已中止时抛出
     */
    private static void assertSessionOpen(UploadSession session) {
        if (session.closed) {
            throw new IllegalStateException("分片上传会话已结束，拒绝迟到请求: " + session.uploadId);
        }
    }

    /**
     * 登记已完成会话标识，并按上限淘汰最旧记录；淘汰后迟到请求退化为「会话不存在」，仍是业务异常而非 500。
     *
     * @param uploadId 已完成的上传会话标识
     */
    private synchronized void rememberClosed(String uploadId) {
        if (closedUploads.putIfAbsent(uploadId, Boolean.TRUE) != null) {
            return;
        }
        closedUploadOrder.add(uploadId);
        while (closedUploadOrder.size() > MAX_TRACKED_CLOSED_UPLOADS) {
            String evicted = closedUploadOrder.poll();
            if (evicted != null) {
                closedUploads.remove(evicted);
            }
        }
    }

    /**
     * 把分片文件写入合并流，同时重算 SHA-256 并与登记 etag 比对，防止内存记录与磁盘内容错位。
     *
     * @param partFile 分片文件
     * @param target 合并输出流
     * @param expected 调用方确认且与内存记录一致的分片
     * @throws IOException 读取失败或文件内容与登记 etag 不一致时抛出
     */
    private static void copyAndVerify(
            Path partFile, OutputStream target, MultipartUpload.Part expected) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(partFile)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                target.write(buffer, 0, read);
            }
        }
        String actualEtag = HexFormat.of().formatHex(digest.digest());
        if (!actualEtag.equals(expected.etag())) {
            throw new IOException("分片 " + expected.partNumber() + " 的内容与登记 etag 不一致，已拒绝完成上传");
        }
    }

    /**
     * 校验 uploadId 只含安全字符，避免被拼进临时目录路径时越界。
     *
     * @param uploadId 上传会话标识
     */
    private static void validateUploadId(String uploadId) {
        if (uploadId == null || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw new IllegalArgumentException("uploadId 格式非法");
        }
    }

    /**
     * 校验调用方给出的分片列表与已上传记录完全一致。
     *
     * @param session 上传会话
     * @param ordered 按分片号排序的调用方列表
     */
    private static void verifyParts(UploadSession session, List<MultipartUpload.Part> ordered) {
        int previousPartNumber = 0;
        for (MultipartUpload.Part part : ordered) {
            if (part.partNumber() <= previousPartNumber) {
                throw new IllegalArgumentException("分片号不能重复: " + part.partNumber());
            }
            previousPartNumber = part.partNumber();
            MultipartUpload.Part uploaded = session.parts.get(part.partNumber());
            if (uploaded == null) {
                throw new IllegalArgumentException("分片 " + part.partNumber() + " 尚未上传");
            }
            if (!uploaded.etag().equals(part.etag())) {
                throw new IllegalArgumentException("分片 " + part.partNumber() + " 的 etag 不匹配");
            }
        }
    }

    /**
     * 分片临时文件名。
     *
     * @param partNumber 分片号
     * @return 文件名
     */
    private static String partFileName(int partNumber) {
        return "part-" + partNumber + ".bin";
    }

    /**
     * 获取 SHA-256 摘要器；JDK 必带该算法，缺失视为运行时环境损坏。
     *
     * @return 摘要器
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /**
     * 自底向上删除空目录，直到根目录为止；目录非空时停止。
     *
     * @param directory 起始目录
     * @throws IOException 删除失败时抛出
     */
    private void cleanupEmptyParents(Path directory) throws IOException {
        Path current = directory;
        while (current != null && current.startsWith(root) && !current.equals(root)) {
            try (Stream<Path> entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    /**
     * 递归删除目录内容；失败只记 WARN，不影响已经完成的合并或中止操作。
     *
     * @param directory 待删除目录
     */
    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    log.warn("failed to delete multipart temp entry", e);
                                }
                            });
        } catch (IOException e) {
            log.warn("failed to clean multipart temp directory", e);
        }
    }

    /** 一次分片上传的进程内状态。 */
    private static final class UploadSession {

        private final String uploadId;
        private final String key;
        private final Path target;
        private final String contentType;
        private final Instant initiatedAt;
        private final Path directory;
        private final ConcurrentMap<Integer, MultipartUpload.Part> parts;

        /** 会话生命周期锁：分片上传持读锁，complete/abort 持写锁。 */
        private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);

        /** 按分片号串行化「写文件 + 登记 etag」，保证并发重传同一分片时二者原子一致。 */
        private final ConcurrentMap<Integer, Lock> partLocks = new ConcurrentHashMap<>();

        /** 会话是否已结束；由生命周期写锁保护写，volatile 保证持读锁的分片上传可见。 */
        private volatile boolean closed;

        /**
         * 创建会话状态。
         *
         * @param uploadId 会话标识
         * @param key 目标对象键
         * @param target 目标文件路径
         * @param contentType MIME 类型
         * @param initiatedAt 创建时间
         * @param directory 分片临时目录
         * @param parts 已上传分片
         */
        private UploadSession(
                String uploadId,
                String key,
                Path target,
                String contentType,
                Instant initiatedAt,
                Path directory,
                ConcurrentMap<Integer, MultipartUpload.Part> parts) {
            this.uploadId = uploadId;
            this.key = key;
            this.target = target;
            this.contentType = contentType;
            this.initiatedAt = initiatedAt;
            this.directory = directory;
            this.parts = parts;
        }

        /**
         * 转成对外 DTO。
         *
         * @return 分片上传快照
         */
        private MultipartUpload toUpload() {
            return new MultipartUpload(
                    uploadId,
                    key,
                    contentType,
                    initiatedAt,
                    parts.values().stream()
                            .sorted(Comparator.comparingInt(MultipartUpload.Part::partNumber))
                            .toList());
        }
    }
}
