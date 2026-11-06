package com.agenthub.runtime.domain.model;

/**
 * 一段合成音频。所属域：runtime。
 *
 * <p>只在当前请求的内存里存活：不落业务表、不进对象存储，响应写完即由 GC 回收。
 * 之所以带上 content type，是为了让控制器直接写对 {@code Content-Type}，
 * 不必在前端猜格式（换音色/格式时前端无需同步改代码）。
 *
 * @param bytes       音频字节
 * @param contentType 音频 MIME（如 audio/mpeg）
 */
public record SpeechAudio(byte[] bytes, String contentType) {
}
