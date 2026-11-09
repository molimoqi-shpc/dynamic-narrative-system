package com.bs.narrative.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 全局异常处理器（毕设级：统一错误响应格式 {code, message, traceId, timestamp}）。
 *
 * 错误响应格式：
 * {
 *   "code": 100001,
 *   "message": "缺少必填参数: sceneId",
 *   "traceId": "a1b2c3d4...",
 *   "timestamp": "2026-09-05T10:00:00Z"
 * }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按 ErrorCode 映射 HTTP 状态。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        HttpStatus status = mapHttpStatus(code);
        String traceId = generateTraceId();
        log.warn("[BusinessException] traceId={} code={} message={} retriable={}",
                traceId, code.getCode(), e.getMessage(), e.isRetriable());
        return ResponseEntity.status(status)
                .body(new ApiError(code.getCode(), e.getMessage(), traceId, Instant.now().toString()));
    }

    /** Bean Validation 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String traceId = generateTraceId();
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[Validation] traceId={} errors={}", traceId, msg);
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.PARAM_INVALID.getCode(),
                        "参数校验失败: " + msg, traceId, Instant.now().toString()));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleInvalidRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError(ErrorCode.PARAM_INVALID.getCode(),
                "请求字段缺失或类型不正确", generateTraceId(), Instant.now().toString()));
    }

    /** 兜底：未预料的异常。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception e) {
        String traceId = generateTraceId();
        log.error("[UnhandledException] traceId={}", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ErrorCode.INTERNAL_ERROR.getCode(),
                        "系统内部错误，请稍后重试并提供追踪编号", traceId, Instant.now().toString()));
    }

    private HttpStatus mapHttpStatus(ErrorCode code) {
        if(code==ErrorCode.AUTH_FORBIDDEN) return HttpStatus.FORBIDDEN;
        if(code==ErrorCode.AUTH_REQUIRED || code==ErrorCode.AUTH_INVALID_CREDENTIALS) return HttpStatus.UNAUTHORIZED;
        int c = code.getCode();
        if (c >= 100000 && c < 200000) return HttpStatus.BAD_REQUEST;   // 参数
        if (c >= 200000 && c < 300000) return HttpStatus.NOT_FOUND;     // 资源
        if (c >= 300000 && c < 400000) return HttpStatus.BAD_GATEWAY;   // 外部服务
        if (c >= 400000 && c < 500000) return HttpStatus.CONFLICT;      // 业务规则
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 统一错误响应体。 */
    public record ApiError(
            int code,
            String message,
            String traceId,
            String timestamp
    ) {}
}
