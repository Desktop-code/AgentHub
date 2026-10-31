package com.agenthub.obs.domain.model;

/**
 * 调用和步骤状态。所属域：obs。
 * 创建时为 RUNNING，结束才写成终态。
 */
public enum ObsStatus {

    /** 已创建、尚未结束 */
    RUNNING,
    /** 正常结束 */
    OK,
    /** 失败结束 */
    ERROR,
    /** 取消 */
    CANCELLED
}
