package com.agenthub.audit.domain;

/**
 * 登录日志写入口。所属域：audit。
 * 调用方：auth 登录流程。实现必须独立提交，避免登录失败把日志一起滚掉。
 */
public interface LoginLogWriter {

    /**
     * @param userId   平台用户 id，识别失败时为 null
     * @param username 尝试登录的名字
     * @param message  给人看的结果说明，不要带密码
     */
    void success(Long userId, String username, String message);

    /**
     * @param username 尝试登录的名字
     * @param message  失败原因，不要带密码
     */
    void failure(String username, String message);
}
