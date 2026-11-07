/**
 * Token 计费。所属域：billing。
 * 只记账、汇总和单价，不调用模型，也不替代 runtime 执行。
 * 日汇总：控制台可按区间重算；定时任务每小时 10/30/50 分幂等汇总当日。
 */
package com.agenthub.billing;
