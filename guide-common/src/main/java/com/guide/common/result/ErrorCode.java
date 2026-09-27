package com.guide.common.result;

/**
 * 业务错误码：0 成功，4xxxx 客户端错误，5xxxx 服务端错误（与短链平台编码风格一致）
 */
public enum ErrorCode {

    SUCCESS(0, "success"),
    PARAM_ERROR(40000, "参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权限"),
    NOT_FOUND(40400, "资源不存在"),
    QUESTION_EMPTY(40001, "问题不能为空"),
    FILE_TOO_LARGE(40002, "文件过大"),
    FILE_TYPE_INVALID(40003, "仅支持 txt 文件"),
    KNOWLEDGE_NOT_FOUND(40401, "知识文档不存在"),
    SYSTEM_ERROR(50000, "系统内部错误"),
    LLM_UNAVAILABLE(50001, "大模型服务暂不可用"),
    TTS_UNAVAILABLE(50002, "语音合成服务暂不可用"),
    SHORTLINK_UNAVAILABLE(50003, "短链平台暂不可用");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
