package io.github.codejinxz.yilian.upload.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchIOException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.storage.ObjectStorage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link StorageLineReader} 的行读取边界单测：内存对象存储承载内容分支，Mockito 承载 失败分支，不启动 Spring 容器。
 *
 * <p>覆盖导入链路依赖的主分支：逐行顺序、BOM 剥离、无尾换行、CRLF、超长行（管道缓冲 背压）、存储读失败包装成 IOException、消费侧异常原样传播。生产线程 30s 超时分支
 * 需要真实阻塞，留给集成环境，不在单测里制造慢用例。
 */
class StorageLineReaderTest {

    /** 写入内存对象并逐行读取；行收集到列表返回。 */
    private List<String> readLines(String key, byte[] content) throws IOException {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        storage.put(key, new ByteArrayInputStream(content), content.length, "text/plain");
        List<String> lines = new ArrayList<>();
        StorageLineReader.forEachLine(storage, key, lines::add);
        return lines;
    }

    /** [AC-3] 常规多行对象按序逐行交付，不丢行、不重排。 */
    @Test
    void readsLinesInOrder_ac3() throws IOException {
        List<String> lines =
                readLines("import/a.json", "第一行\n第二行\n第三行\n".getBytes(StandardCharsets.UTF_8));

        assertThat(lines).containsExactly("第一行", "第二行", "第三行");
    }

    /** [AC-3] 空对象：零行交付且不抛错（空文件是合法导入输入，报错会挡住空题库导入）。 */
    @Test
    void deliversNoLinesForEmptyObject_ac3() throws IOException {
        List<String> lines = readLines("import/empty.json", new byte[0]);

        assertThat(lines).isEmpty();
    }

    /** [AC-3] UTF-8 BOM 只从首行剥掉：Excel 导出的 CSV 依赖此分支，BOM 混进首个字段会污染指纹。 */
    @Test
    void stripsBomFromFirstLineOnly_ac3() throws IOException {
        String bom = "\uFEFF";
        List<String> lines =
                readLines(
                        "import/bom.csv", (bom + "zh,ref\n第二行\n").getBytes(StandardCharsets.UTF_8));

        assertThat(lines).containsExactly("zh,ref", "第二行");
    }

    /** [AC-3] 无尾换行：最后一行仍交付（手工编辑过的 JSON 常见形态）。 */
    @Test
    void deliversLastLineWithoutTrailingNewline_ac3() throws IOException {
        List<String> lines =
                readLines("import/no-eol.json", "甲\n乙".getBytes(StandardCharsets.UTF_8));

        assertThat(lines).containsExactly("甲", "乙");
    }

    /** [AC-3] CRLF 行尾：\r 不混进行内容（Windows 编辑的 CSV）。 */
    @Test
    void stripsCarriageReturnOnCrLf_ac3() throws IOException {
        List<String> lines =
                readLines("import/crlf.csv", "甲\r\n乙\r\n".getBytes(StandardCharsets.UTF_8));

        assertThat(lines).containsExactly("甲", "乙");
    }

    /** [AC-3] 超长行（远超 64KB 管道缓冲）：一行完整交付不截断——管道背压路径的真实意义。 */
    @Test
    void deliversSingleLineLargerThanPipeBuffer_ac3() throws IOException {
        String longLine = "x".repeat(200 * 1024);
        List<String> lines =
                readLines(
                        "import/long-line.json",
                        (longLine + "\n尾行\n").getBytes(StandardCharsets.UTF_8));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).hasSize(200 * 1024);
        assertThat(lines.get(1)).isEqualTo("尾行");
    }

    /** [AC-3] 存储读失败：包装成 IOException 向上抛（批次标 FAILED 可 resume 的触发点），原异常为 cause。 */
    @Test
    void wrapsStorageFailureInIOException_ac3() throws Exception {
        ObjectStorage storage = mock(ObjectStorage.class);
        IOException readFailure = new IOException("磁盘不可读");
        when(storage.get(anyString(), any(OutputStream.class))).thenThrow(readFailure);

        IOException thrown =
                catchIOException(
                        () -> StorageLineReader.forEachLine(storage, "import/x.json", line -> {}));

        assertThat(thrown).isNotNull().hasMessageContaining("读取导入文件失败");
        assertThat(thrown.getCause()).isSameAs(readFailure);
    }

    /** [AC-3] 消费侧异常原样传播：行校验抛出的错误不能被吞掉或换成笼统 IOException。 */
    @Test
    void propagatesConsumerException_ac3() throws IOException {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        byte[] content = "甲\n乙\n".getBytes(StandardCharsets.UTF_8);
        storage.put(
                "import/bad.json", new ByteArrayInputStream(content), content.length, "text/plain");
        IllegalStateException consumerFailure = new IllegalStateException("行 2 校验失败");

        assertThatThrownBy(
                        () ->
                                StorageLineReader.forEachLine(
                                        storage,
                                        "import/bad.json",
                                        line -> {
                                            if (line.equals("乙")) {
                                                throw consumerFailure;
                                            }
                                        }))
                .isSameAs(consumerFailure);
    }

    /** [AC-3] 参数防御：storage/key/consumer 为 null 一律 NPE，不给「静默空读」留口子。 */
    @Test
    void rejectsNullArguments_ac3() {
        ObjectStorage storage = mock(ObjectStorage.class);
        java.util.function.Consumer<String> consumer = line -> {};

        assertThatThrownBy(() -> StorageLineReader.forEachLine(null, "k", consumer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StorageLineReader.forEachLine(storage, null, consumer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StorageLineReader.forEachLine(storage, "k", null))
                .isInstanceOf(NullPointerException.class);
    }
}
