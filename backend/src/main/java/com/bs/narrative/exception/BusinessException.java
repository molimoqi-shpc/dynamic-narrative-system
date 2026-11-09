package com.bs.narrative.exception;

import lombok.Getter;

/**
 * 业务异常基类（毕设级：所有业务层异常必须使用此类型，便于统一处理）。
 *
 * 设计原则：
 *   - 区分可恢复（retriable）与不可恢复
 *   - 必须携带 ErrorCode，禁止抛 RuntimeException
 *   - 禁止 try-catch 后只 log 不抛
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean retriable;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, false, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, false, cause);
    }

    public BusinessException(ErrorCode errorCode, String message, boolean retriable) {
        this(errorCode, message, retriable, null);
    }

    public BusinessException(ErrorCode errorCode, String message, boolean retriable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retriable = retriable;
    }

    public int getCode() {
        return errorCode.getCode();
    }
}
