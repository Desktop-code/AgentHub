/**
 * 运行时域。
 * 职责：控制台试跑的会话、事件，以及 AgentScope ReAct / 一层 Router 装配。所属域：runtime。
 * 约束：身份是平台用户；不接 apiKey；不把对话全文写入操作日志。
 */
package com.agenthub.runtime;
