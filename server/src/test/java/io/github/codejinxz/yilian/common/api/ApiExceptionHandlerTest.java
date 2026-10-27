package io.github.codejinxz.yilian.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitExceededException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ApiExceptionHandler} 的 4xx 分支回归测试。
 *
 * <p>415、类型不匹配、缺参、参数级校验走 MockMvc 独立环境（不启动数据库、不连外部服务）， {@link ConstraintViolationException} 无法由 MVC
 * 入参绑定自然触发，直接构造后调用 handler； 另外保留一条越权 403 断言，确保新增分支没有改写安全语义。
 */
class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    private final ErrorLogRecorder errorLogRecorder = mock(ErrorLogRecorder.class);
    private final ApiExceptionHandler handler = new ApiExceptionHandler(errorLogRecorder);

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TestController())
                        .setControllerAdvice(handler)
                        .build();
    }

    /**
     * 向只接受 JSON 的接口发送 text/plain，应得到 415 而不是 500。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ApiCode.UNSUPPORTED_MEDIA_TYPE.code()))
                .andExpect(jsonPath("$.message").value(ApiCode.UNSUPPORTED_MEDIA_TYPE.message()));
    }

    /**
     * 路径变量不是 long 时，应归入参数校验失败并说明期望类型。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    void typeMismatchReturns400() throws Exception {
        mockMvc.perform(get("/test/type/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("id"))
                .andExpect(jsonPath("$.data.errors[0].message").value("参数类型不正确，应为 Long"));
    }

    /**
     * 缺少必填查询参数时，应回 400 并在 data.errors 中给出参数名。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    void missingParameterReturns400() throws Exception {
        mockMvc.perform(get("/test/missing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("keyword"))
                .andExpect(jsonPath("$.data.errors[0].message").value("缺少必填参数"));
    }

    /**
     * Spring 6.1+/Boot 4 的参数级校验由 HandlerMethodValidationException 承载，应回 400。
     *
     * @throws Exception MockMvc 调用异常
     */
    @Test
    void handlerMethodValidationReturns400() throws Exception {
        mockMvc.perform(get("/test/paged").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("page"))
                .andExpect(jsonPath("$.data.errors[0].message").value("页码必须大于 0"));
    }

    /** 直接构造 jakarta 约束违反异常，验证 Service 层方法校验同样回 400。 */
    @Test
    void constraintViolationReturns400() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<SampleRequest>> violations =
                validator.validate(new SampleRequest(""));
        assertThat(violations).isNotEmpty();

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                handler.handleConstraintViolation(new ConstraintViolationException(violations));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ApiCode.VALIDATION_FAILED.code());
        assertThat(errorsOf(response))
                .anySatisfy(
                        error -> {
                            assertThat(error.get("field")).isEqualTo("keyword");
                            assertThat(error.get("message")).isEqualTo("关键字不能为空");
                        });
    }

    /**
     * 绕过 MVC 直接构造参数级校验异常，确保 data.errors 中保留参数名与约束提示。
     *
     * @throws Exception 反射获取测试方法失败
     */
    @Test
    void handlerMethodValidationKeepsParameterNameAndMessage() throws Exception {
        Method method = TestController.class.getDeclaredMethod("paged", int.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        ParameterValidationResult result =
                new ParameterValidationResult(
                        parameter,
                        0,
                        List.of(new FieldError("page", "page", "页码必须大于 0")),
                        null,
                        null,
                        null,
                        null);
        MethodValidationResult validationResult =
                MethodValidationResult.create(new TestController(), method, List.of(result));

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                handler.handleHandlerMethodValidation(
                        new HandlerMethodValidationException(validationResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ApiCode.VALIDATION_FAILED.code());
        assertThat(errorsOf(response))
                .anySatisfy(
                        error -> {
                            assertThat(error.get("field")).isEqualTo("page");
                            assertThat(error.get("message")).isEqualTo("页码必须大于 0");
                        });
    }

    /** 越权仍应保持 403，不能被参数校验分支吞掉或改成 400。 */
    @Test
    void accessDeniedStillReturns403() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("没有访问权限"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ApiCode.ACCESS_DENIED.code());
    }

    /** 限流必须回 429，并把等待秒数写进 Retry-After 响应头。 */
    @Test
    void rateLimitedReturns429WithRetryAfter() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRateLimited(new RateLimitExceededException(42));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ApiCode.RATE_LIMITED.code());
        assertThat(response.getBody().getMessage()).contains("42");
    }

    /** 幂等冲突走业务异常分支，HTTP 状态必须映射为 409。 */
    @Test
    void idempotentConflictReturns409() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ApiCode.IDEMPOTENT_CONFLICT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ApiCode.IDEMPOTENT_CONFLICT.code());
    }

    /** 业务异常属于已知结果，不能写入 error_log。 */
    @Test
    void businessExceptionIsNotRecorded() {
        handler.handleBusiness(new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS));

        verifyNoInteractions(errorLogRecorder);
    }

    /** 未知异常应写入 error_log 记录器，仍然返回通用 500。 */
    @Test
    void unexpectedExceptionIsRecorded() {
        HttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/test/boom");
        IllegalStateException exception = new IllegalStateException("boom");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(errorLogRecorder).record(exception, request);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> errorsOf(
            ResponseEntity<ApiResponse<Map<String, Object>>> response) {
        Map<String, Object> data = response.getBody().getData();
        assertThat(data).isNotNull();
        assertThat(data.get("errors")).isInstanceOf(List.class);
        return (List<Map<String, String>>) data.get("errors");
    }

    /** 仅用于触发各类请求侧异常的测试控制器，不依赖任何外部服务。 */
    @RestController
    static class TestController {

        @PostMapping(value = "/test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        public ApiResponse<Void> body(@RequestBody Map<String, Object> body) {
            return ApiResponse.success();
        }

        @GetMapping("/test/type/{id}")
        public ApiResponse<Long> type(@PathVariable Long id) {
            return ApiResponse.success(id);
        }

        @GetMapping("/test/missing")
        public ApiResponse<String> missing(@RequestParam String keyword) {
            return ApiResponse.success(keyword);
        }

        @GetMapping("/test/paged")
        public ApiResponse<Integer> paged(
                @RequestParam @Min(value = 1, message = "页码必须大于 0") int page) {
            return ApiResponse.success(page);
        }
    }

    /** 用于生成真实 ConstraintViolation 的简单载体。 */
    static class SampleRequest {

        @NotBlank(message = "关键字不能为空")
        private final String keyword;

        SampleRequest(String keyword) {
            this.keyword = keyword;
        }
    }
}
