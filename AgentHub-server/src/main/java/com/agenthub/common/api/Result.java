package com.agenthub.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体。所属域：common。成功时 code 为 0。
 *
 * @param <T> 业务数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private int code;
    private String msg;
    private T data;

    /**
     * 成功且无数据。
     *
     * @param <T> 数据类型
     * @return code=0 的响应
     */
    public static <T> Result<T> ok() {
        return new Result<>(0, "ok", null);
    }

    /**
     * 成功并携带数据。
     *
     * @param data 返回给前端的数据，允许为 null
     * @param <T>  数据类型
     * @return code=0 的响应
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    /**
     * 失败响应。由全局异常处理器转换成对应 HTTP 状态。
     *
     * @param code 业务错误码，非 0
     * @param msg  给前端看的说明
     * @param <T>  数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}
