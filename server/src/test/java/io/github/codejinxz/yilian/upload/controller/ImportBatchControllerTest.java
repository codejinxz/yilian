package io.github.codejinxz.yilian.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.ApiExceptionHandler;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogAspect;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.logging.OperationLogRecorder;
import io.github.codejinxz.yilian.upload.api.ImportExceptionHandler;
import io.github.codejinxz.yilian.upload.dto.ImportBatchAccepted;
import io.github.codejinxz.yilian.upload.dto.ImportBatchStatusResponse;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;
import io.github.codejinxz.yilian.upload.service.impl.ImportBatchServiceImpl;
import io.github.codejinxz.yilian.upload.support.ImportFilePolicy;
import io.github.codejinxz.yilian.upload.support.InMemoryImportBatchDao;
import io.github.codejinxz.yilian.upload.support.InMemoryObjectStorage;
import io.github.codejinxz.yilian.upload.validation.DefaultImportRowValidator;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 导入接口的 MockMvc 测试：413、415、扩展名、5MB 边界、202、轮询、失败摘要与完成态 resume。
 *
 * <p>使用真实 Service 但替换为内存 Dao / 内存对象存储 / 单线程执行器，不启动 Spring 容器， 因此不依赖 MySQL、Redis 或本地磁盘。
 */
class ImportBatchControllerTest {

    private InMemoryImportBatchDao dao;

    private InMemoryObjectStorage storage;

    private ExecutorService executor;

    private ImportBatchServiceImpl service;

    private ImportBatchController controller;

    private MockMvc mockMvc;

    private final OperationLogRecorder operationLogRecorder = mock(OperationLogRecorder.class);

    private final OperationLogAspect aspect = new OperationLogAspect(operationLogRecorder);

    @BeforeEach
    void setUp() {
        // 防御性清场：切面按 SecurityContext 抓 actor，用例顺序不定时遗留上下文会让断言耦合。
        SecurityContextHolder.clearContext();
        dao = new InMemoryImportBatchDao();
        storage = new InMemoryObjectStorage();
        executor = Executors.newSingleThreadExecutor();
        service =
                new ImportBatchServiceImpl(
                        storage,
                        dao,
                        List.of(new DefaultImportRowValidator(new ObjectMapper())),
                        executor);
        controller = new ImportBatchController(service);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(
                                new ImportExceptionHandler(),
                                new ApiExceptionHandler(mock(ErrorLogRecorder.class)))
                        .build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        // 裸 MockMvc 不经过切面，Controller 写入的上下文与 SecurityContext 需由测试主动清理。
        OperationLogContext.clear();
        SecurityContextHolder.clearContext();
    }

    /** 合法 CSV 上传返回 202，响应只含 batchId 与 UPLOADED 状态。 */
    @Test
    void uploadReturns202WithBatchId() throws Exception {
        MockMultipartFile file =
                file(
                        "sentences.csv",
                        "text/csv",
                        "zh,ref\n他,He\n".getBytes(StandardCharsets.UTF_8));

        MvcResult result =
                mockMvc.perform(multipart("/api/admin/uploads").file(file))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                        .andExpect(jsonPath("$.data.batchId").isNotEmpty())
                        .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                        .andReturn();

        String batchId = batchIdOf(result);
        awaitStatus(batchId, ImportBatchStatus.COMPLETED);
        mockMvc.perform(get("/api/admin/uploads/{id}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.processedRows").value(2))
                .andExpect(jsonPath("$.data.successRows").value(2));
    }

    /**
     * 上传成功后把动态 batchId 写入上下文，切面落库参数的 targetId 必须非空且等于响应中的批次 id。
     *
     * @throws Throwable 切面执行异常
     */
    @SuppressWarnings("unchecked")
    @Test
    void uploadRecordsBatchIdFromContext() throws Throwable {
        MockMultipartFile file =
                file(
                        "sentences.csv",
                        "text/csv",
                        "zh,ref\n他,He\n".getBytes(StandardCharsets.UTF_8));

        ProceedingJoinPoint joinPoint = proceeding(() -> controller.upload(file, null));
        ResponseEntity<ApiResponse<ImportBatchAccepted>> response =
                (ResponseEntity<ApiResponse<ImportBatchAccepted>>)
                        aspect.around(joinPoint, operationLog("upload"));

        assertThat(response.getBody()).isNotNull();
        String batchId = response.getBody().getData().batchId();
        assertThat(batchId).isNotBlank();
        verify(operationLogRecorder)
                .record(
                        any(),
                        eq("import-batch"),
                        eq(batchId),
                        eq("管理员上传导入文件"),
                        eq("SUCCESS"),
                        anyLong(),
                        isNull(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
        // 任务结束后 ThreadLocal 必须清空，避免下一次请求串用批次 id。
        assertThat(OperationLogContext.current()).isEmpty();
    }

    /**
     * resume 同样把路径变量中的批次 id 交给上下文，保证审计日志能定位到具体批次。
     *
     * @throws Throwable 切面执行异常
     */
    @Test
    void resumeRecordsBatchIdFromContext() throws Throwable {
        MockMultipartFile file =
                file(
                        "sentences.json",
                        "application/json",
                        "{\"zh\":\"a\"}\n".getBytes(StandardCharsets.UTF_8));
        MvcResult result =
                mockMvc.perform(multipart("/api/admin/uploads").file(file))
                        .andExpect(status().isAccepted())
                        .andReturn();
        String batchId = batchIdOf(result);
        awaitStatus(batchId, ImportBatchStatus.COMPLETED);
        // 裸 MockMvc 不经过切面，手动模拟请求边界，验证切面自身的清理与覆盖。
        OperationLogContext.clear();

        ProceedingJoinPoint joinPoint = proceeding(() -> controller.resume(batchId));
        Object response = aspect.around(joinPoint, operationLog("resume"));

        assertThat(response).isInstanceOf(ApiResponse.class);
        verify(operationLogRecorder)
                .record(
                        any(),
                        eq("import-batch"),
                        eq(batchId),
                        eq("管理员续跑导入批次"),
                        eq("SUCCESS"),
                        anyLong(),
                        isNull(),
                        isNull(),
                        eq("unknown"),
                        eq("SYSTEM"));
        assertThat(OperationLogContext.current()).isEmpty();
    }

    /** 扩展名不在白名单时返回 415，而不是 400 或 500。 */
    @Test
    void uploadRejectsUnsupportedExtension() throws Exception {
        MockMultipartFile file =
                file("sentences.txt", "text/plain", "zh,ref\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/admin/uploads").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ApiCode.UNSUPPORTED_MEDIA_TYPE.code()));
    }

    /** MIME 不在白名单时返回 415。 */
    @Test
    void uploadRejectsUnsupportedMime() throws Exception {
        MockMultipartFile file =
                file(
                        "sentences.csv",
                        "application/pdf",
                        "zh,ref\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/admin/uploads").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ApiCode.UNSUPPORTED_MEDIA_TYPE.code()));
    }

    /** 超过 5MB 一个字节返回 413，业务码复用参数错误号段。 */
    @Test
    void uploadRejectsFileLargerThanFiveMegabytes() throws Exception {
        byte[] content = new byte[(int) ImportFilePolicy.MAX_FILE_SIZE_BYTES + 1];
        MockMultipartFile file = file("sentences.csv", "text/csv", content);

        mockMvc.perform(multipart("/api/admin/uploads").file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(
                        jsonPath("$.message").value(org.hamcrest.Matchers.containsString("5MB")));
    }

    /** 缺少 file 字段时返回 400，而不是落入公共 500 兜底。 */
    @Test
    void uploadWithoutFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/api/admin/uploads"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
    }

    /** 失败行摘要接口返回计数与行级原因。 */
    @Test
    void errorsEndpointReturnsFailedRowsSummary() throws Exception {
        MockMultipartFile file =
                file(
                        "sentences.json",
                        "application/json",
                        "{\"zh\":\"a\"}\n{bad json}\n".getBytes(StandardCharsets.UTF_8));

        MvcResult result =
                mockMvc.perform(multipart("/api/admin/uploads").file(file))
                        .andExpect(status().isAccepted())
                        .andReturn();
        String batchId = batchIdOf(result);
        awaitStatus(batchId, ImportBatchStatus.COMPLETED);

        mockMvc.perform(get("/api/admin/uploads/{id}/errors", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andExpect(jsonPath("$.data.errors[0].lineNumber").value(2))
                .andExpect(jsonPath("$.data.errors[0].reason").value("非法 JSON 行"));
    }

    /** 已完成批次再次 resume 返回 COMPLETED，不重新读文件。 */
    @Test
    void resumeCompletedBatchIsIdempotent() throws Exception {
        MockMultipartFile file =
                file(
                        "sentences.json",
                        "application/json",
                        "{\"zh\":\"a\"}\n{\"zh\":\"b\"}\n".getBytes(StandardCharsets.UTF_8));
        MvcResult result =
                mockMvc.perform(multipart("/api/admin/uploads").file(file))
                        .andExpect(status().isAccepted())
                        .andReturn();
        String batchId = batchIdOf(result);
        awaitStatus(batchId, ImportBatchStatus.COMPLETED);
        int reads = storage.getCount();

        mockMvc.perform(post("/api/admin/uploads/{id}/resume", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.processedRows").value(2));

        assertThat(storage.getCount()).isEqualTo(reads);
    }

    /** 不存在的批次返回 404。 */
    @Test
    void statusUnknownBatchReturns404() throws Exception {
        mockMvc.perform(get("/api/admin/uploads/{id}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.NOT_FOUND.code()));
    }

    /**
     * 构造只负责执行目标调用的 join point，避免在单元测试中启动 Spring AOP。
     *
     * @param action 被切面包围的实际调用
     * @return 可传给切面的 join point
     * @throws Throwable 目标调用抛出的异常
     */
    private static ProceedingJoinPoint proceeding(Callable<?> action) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenAnswer(invocation -> action.call());
        return joinPoint;
    }

    /**
     * 按方法名读取 Controller 上的操作日志注解。
     *
     * @param methodName Controller 方法名
     * @return 方法上的操作日志注解
     */
    private static OperationLog operationLog(String methodName) {
        return Arrays.stream(ImportBatchController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .map(method -> method.getAnnotation(OperationLog.class))
                .orElseThrow(() -> new IllegalStateException("找不到测试方法: " + methodName));
    }

    /**
     * 构造 multipart 文件。
     *
     * @param filename 文件名
     * @param contentType MIME
     * @param content 内容
     * @return multipart 文件
     */
    private static MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    /**
     * 从 202 响应体取 batchId。
     *
     * @param result MockMvc 结果
     * @return 批次 id
     * @throws Exception 读取响应体失败
     */
    private static String batchIdOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.batchId");
    }

    /**
     * 轮询直到目标状态或超时。
     *
     * @param batchId 批次 id
     * @param expected 目标状态
     * @return 最后状态
     */
    private ImportBatchStatusResponse awaitStatus(String batchId, ImportBatchStatus expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ImportBatchStatusResponse last = null;
        while (System.nanoTime() < deadline) {
            last = service.status(batchId);
            if (last.status() == expected) {
                return last;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待导入状态被中断", exception);
            }
        }
        throw new AssertionError(
                "等待状态 " + expected + " 超时，最后状态: " + (last == null ? "null" : last.status()));
    }
}
