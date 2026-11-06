package com.agenthub.runtime.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 语音听写的输入约束。所属域：runtime。
 *
 * <p>为什么在服务端还要再校验一遍格式与体积：浏览器能录的容器不止一种
 * （Chrome 出 webm/opus，Safari 出 mp4/m4a），前端只做「能录就录」，
 * 服务端才是真正的闸——超限要给出明确中文原因，而不是把 30MB 音频转发给上游再等超时。
 *
 * <p>格式白名单按 OpenAI 兼容语音接口的接受面选取：
 * <b>webm / wav / mp3 / m4a(mp4) / ogg</b>。判断只看文件头魔数，不看扩展名与 Content-Type，
 * 因为这两者都由客户端随便填。
 *
 * <p>音频本身不落盘：校验完直接进内存出站，用完即弃。
 * 线程：无状态，静态纯函数。
 */
public final class VoiceAudioPolicy {

    /** 录音过短的下限：低于这个体积基本是空录（如刚按下就松手） */
    public static final int MIN_BYTES = 512;

    private VoiceAudioPolicy() {
    }

    /**
     * 音频格式。扩展名用于给上游判断容器，MIME 用于 multipart 的 Content-Type。
     *
     * @param extension 不带点的扩展名（webm / wav / mp3 / m4a / ogg）
     * @param mimeType  对应 MIME
     */
    public record Format(String extension, String mimeType) {
    }

    /**
     * 校验一段录音，通过则返回识别出的格式。
     *
     * @param audio    音频字节，可空
     * @param maxBytes 体积上限（含）；来自配置，避免把上限写死在两处
     * @return 识别出的格式
     * @throws BizException 空录音、过短、超限或格式不在白名单；文案给用户看
     */
    public static Format require(byte[] audio, int maxBytes) {
        if (audio == null || audio.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有收到录音，请按住说话后再试");
        }
        if (audio.length > maxBytes) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "录音超过 " + megabytes(maxBytes) + "MB 上限，请缩短后再试");
        }
        if (audio.length < MIN_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "录音太短，请按住说话后再试");
        }
        Format format = detect(audio);
        if (format == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "音频格式不支持，请使用 webm / wav / mp3 / m4a / ogg 录音");
        }
        return format;
    }

    /**
     * 按文件头识别格式。识别不出返回 null，由调用方给出统一文案。
     *
     * @param audio 音频字节
     * @return 格式；不认识为 null
     */
    public static Format detect(byte[] audio) {
        if (audio == null) {
            return null;
        }
        // WebM/Matroska：EBML 头 1A 45 DF A3（Chrome MediaRecorder 默认）
        if (audio.length >= 4 && (audio[0] & 0xFF) == 0x1A && (audio[1] & 0xFF) == 0x45
                && (audio[2] & 0xFF) == 0xDF && (audio[3] & 0xFF) == 0xA3) {
            return new Format("webm", "audio/webm");
        }
        // WAV：RIFF....WAVE
        if (audio.length >= 12 && ascii(audio, 0, "RIFF") && ascii(audio, 8, "WAVE")) {
            return new Format("wav", "audio/wav");
        }
        // OGG：OggS
        if (audio.length >= 4 && ascii(audio, 0, "OggS")) {
            return new Format("ogg", "audio/ogg");
        }
        // MP4/M4A：第 4~8 字节是 ftyp（Safari MediaRecorder 可能出 audio/mp4）
        if (audio.length >= 12 && ascii(audio, 4, "ftyp")) {
            return new Format("m4a", "audio/mp4");
        }
        // MP3：ID3 标签或帧同步（0xFF 后高 3 位为 1）
        if (audio.length >= 3 && ascii(audio, 0, "ID3")) {
            return new Format("mp3", "audio/mpeg");
        }
        if (audio.length >= 2 && (audio[0] & 0xFF) == 0xFF && (audio[1] & 0xE0) == 0xE0) {
            return new Format("mp3", "audio/mpeg");
        }
        return null;
    }

    /**
     * 体积上限换成人话。整数 MB 直接显示，非整数保留一位小数。
     *
     * @param maxBytes 上限字节
     * @return 如 "10" 或 "0.5"
     */
    private static String megabytes(int maxBytes) {
        double mb = Math.max(maxBytes, 0) / 1024d / 1024d;
        return mb == Math.rint(mb) ? String.valueOf((long) mb) : String.format("%.1f", mb);
    }

    private static boolean ascii(byte[] source, int offset, String expected) {
        if (source.length < offset + expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if ((source[offset + i] & 0xFF) != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
