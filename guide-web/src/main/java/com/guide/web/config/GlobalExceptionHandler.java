package com.guide.web.config;

import com.guide.common.exception.BizException;
import com.guide.common.result.ErrorCode;
import com.guide.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按错误码返回，未知异常兜底 50000 并记录日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        ErrorCode code = e.getErrorCode();
        HttpStatus status;
        if (code == ErrorCode.UNAUTHORIZED) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (code == ErrorCode.FORBIDDEN || code == ErrorCode.NOT_FOUND
                || code == ErrorCode.KNOWLEDGE_NOT_FOUND) {
            status = HttpStatus.NOT_FOUND;
        } else if (code.getCode() >= 50000) {
            // 服务降级类（LLM/TTS/短链不可用）→ 503，与客户端 4xx 错误区分，便于监控告警
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(Result.fail(code.getCode(), e.getMessage()));
    }

    /** 请求体不可读（JSON 格式错误等）→ 参数错误，而非笼统 500 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(Exception e) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.PARAM_ERROR));
    }

    /** 请求方法不匹配（如对 DELETE 端点发 GET）→ 405，避免被笼统包成 500 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(Exception e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(ErrorCode.PARAM_ERROR.getCode(), "请求方法不支持"));
    }

    /** 静态资源/路径不存在 → 404 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }
}
