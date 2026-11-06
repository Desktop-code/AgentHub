package com.agenthub.runtime.infra;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话语音参数。所属域：runtime。对应 {@code agenthub.runtime.voice}。
 *
 * <p>只放「服务端闸门」的两个上限：录音体积与朗读字数。
 * 录音时长不在这里限制——服务端读不到可靠时长，改由前端录音计时器兜住上限；
 * 真正防滥用的仍是体积上限（转发上游前就拒掉）。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.runtime.voice")
public class VoiceProperties {

    /** 单段录音体积上限（字节）。默认 10MB，约等于 60 秒 webm/opus 的量级 */
    private int maxAudioBytes = 10 * 1024 * 1024;

    /** 单次语音合成文字上限（字符）。超限明确报错，前端改走浏览器朗读 */
    private int maxSpeechChars = 4000;
}
