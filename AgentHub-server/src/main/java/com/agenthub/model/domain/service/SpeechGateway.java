package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.SpeechRequest;

/**
 * 语音出站端口。所属域：model 的 domain，实现放在 infra。
 *
 * <p>为什么单开一个端口而不是塞进 {@code ModelClient}：那个接口是「连通探活」语义，
 * 只回脱敏结果、不回业务数据；语音要拿识别文字和音频字节，职责不同。
 *
 * <p>调用方：runtime 的对话语音用例（按住说话 / 朗读），以及模型中心的连通测试。
 * 线程约定：实现必须是阻塞调用，调用方自行切到弹性线程池，禁止占用请求线程的事件循环。
 * 安全约定：实现不得把 {@code apiKey}、音频二进制写进日志或异常文案。
 */
public interface SpeechGateway {

    /**
     * 整段音频 → 文字。
     *
     * @param request  出站参数，capability 必须是 ASR
     * @param audio    音频字节（内存中处理，不落盘）
     * @param fileName 上传文件名，用于给上游识别格式（如 voice.webm）
     * @param mimeType 音频 MIME（如 audio/webm）
     * @return 识别文字；上游返回空文本时由实现抛业务异常
     */
    String transcribe(SpeechRequest request, byte[] audio, String fileName, String mimeType);

    /**
     * 整段文字 → 音频。
     *
     * @param request 出站参数，capability 必须是 TTS
     * @param text    待朗读文字，调用方已做长度校验与裁剪
     * @return 音频字节（mp3 / wav，取决于 request.format）
     */
    byte[] synthesize(SpeechRequest request, String text);
}
