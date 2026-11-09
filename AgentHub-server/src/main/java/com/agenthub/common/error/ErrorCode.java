package com.agenthub.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码。所属域：common。code 给前端分支，HTTP 状态给网关和浏览器。
 */
@Getter
public enum ErrorCode {

    /** 入参不合法 */
    BAD_REQUEST(40000, "参数错误", HttpStatus.BAD_REQUEST),
    /** 用户名或密码错误，两种情况共用一个码，避免探账号 */
    BAD_CREDENTIALS(40003, "用户名或密码错误", HttpStatus.BAD_REQUEST),
    /** 账号存在但已停用 */
    USER_DISABLED(40002, "用户已停用", HttpStatus.BAD_REQUEST),
    /** 唯一键冲突，含软删后仍占用的名字 */
    DUPLICATE(40004, "数据已存在", HttpStatus.BAD_REQUEST),
    /** 删自己或停用自己 */
    CANNOT_CHANGE_SELF(40005, "不能删除或停用当前登录用户", HttpStatus.BAD_REQUEST),
    /** 系统里必须留一个有效的 admin 角色用户 */
    LAST_ADMIN(40006, "不能移除最后一个管理员", HttpStatus.BAD_REQUEST),
    /** 资源不存在或已逻辑删除 */
    NOT_FOUND(40400, "资源不存在", HttpStatus.NOT_FOUND),
    /** 未携带或无法识别的 Token */
    UNAUTHORIZED(40101, "未登录或登录已过期", HttpStatus.UNAUTHORIZED),
    /** 已登录但权限码不匹配 */
    FORBIDDEN(40301, "无权限", HttpStatus.FORBIDDEN),
    /**
     * 幂等键冲突：同键不同内容、或同键仍在执行中。
     * 属请求语义冲突而非系统故障，必须 4xx 明确拒绝，禁止静默覆盖或再跑一遍。
     */
    IDEMPOTENCY_CONFLICT(40901, "幂等键冲突", HttpStatus.CONFLICT),
    /**
     * 提示词组装失败：无生效 L1、用途未配置模板、缺必选段、变量未赋值等。
     * 属「故障」而非业务空结果，必须阻断出站，禁止拼半截 Prompt 送模型。
     */
    PROMPT_UNAVAILABLE(40900, "提示词未就绪，已阻断出站", HttpStatus.CONFLICT),
    /** 未分类的内部错误，对外不带堆栈 */
    INTERNAL(50000, "系统繁忙", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
