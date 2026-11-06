package com.agenthub.model.infra.adapter;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.model.SpeechRequest;
import com.agenthub.model.domain.service.SpeechGateway;
import com.agenthub.model.domain.service.SpeechOptions;
import com.agenthub.model.domain.service.SpeechParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 语音出站。所属域：model 的 infra。
 *
 * <p>两条协议形态：
 * <ul>
 *   <li><b>兼容形态</b>（OPENAI_COMPAT，或 DashScope 的 compatible-mode 基址）：
 *       识别 {@code POST {baseUrl}/audio/transcriptions}（multipart），
 *       合成 {@code POST {baseUrl}/audio/speech}（JSON 进、音频字节出）；</li>
 *   <li><b>DashScope 原生形态</b>（原生 /api/v1 基址）：
 *       识别 {@code POST /services/aigc/multimodal-generation/generation}
 *       （qwen3-asr-flash 同步，音频以 data URL 传入）；
 *       合成 {@code POST /services/audio/tts/SpeechSynthesizer}
 *       （返回 {@code output.audio.data} base64 或 {@code output.audio.url}，适配器再下载成字节）。</li>
 * </ul>
 *
 * <p>为什么不能拿 chat/completions 冒充：那头只会返回文字，前端既播不出来、也识别不了，属于「假成功」。
 * 协议不支持时这里直接抛中文业务错，让前端（朗读）走浏览器降级、（听写）明确提示未绑定。
 * Anthropic 没有语音端点，由矩阵与 {@link #assertProtocolSupported} 双重拦截，明确「该协议不支持此用途」。
 *
 * <p>线程：无状态可并发；出站是阻塞调用，调用方须在弹性线程池执行。
 */
@Component
public class SpeechClient implements SpeechGateway {

    /** DashScope 兼容模式基址特征；与试跑装配的判定保持一致 */
    private static final String COMPATIBLE_MODE_HINT = "compatible-mode";

    /** DashScope 原生合成支持的音频格式；aac / flac 只有兼容口才有 */
    private static final Set<String> NATIVE_FORMATS = Set.of("mp3", "wav", "opus", "pcm");

    /** DashScope 兼容路未配置音色时的官方默认音色；qwen-tts 系列不认 OpenAI 的 alloy */
    private static final String DASHSCOPE_COMPATIBLE_DEFAULT_VOICE = "Cherry";

    /** 原生合成采样率：24000 是官方示例值，且 opus 也支持 */
    private static final int NATIVE_SAMPLE_RATE = 24000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SpeechHttpSupport http;

    /**
     * @param http 语音出站的发送与脱敏
     */
    public SpeechClient(SpeechHttpSupport http) {
        this.http = http;
    }

    /**
     * 整段音频 → 文字。
     *
     * @param request  出站参数
     * @param audio    音频字节
     * @param fileName 上传文件名（扩展名即格式）
     * @param mimeType 音频 MIME
     * @return 识别文字；上游空白结果转成业务异常，避免前端把空串当成功
     * @throws BizException 协议不支持、上游失败或结果为空
     */
    @Override
    public String transcribe(SpeechRequest request, byte[] audio, String fileName, String mimeType) {
        assertProtocolSupported(request);
        if (nativeDashScope(request)) {
            return dashScopeNativeTranscribe(request, audio, mimeType);
        }
        return compatibleTranscribe(request, audio, fileName, mimeType);
    }

    /**
     * 整段文字 → 音频。
     *
     * @param request 出站参数
     * @param text    待朗读文字，调用方已裁剪长度
     * @return 音频字节
     * @throws BizException 协议不支持、格式不支持、上游失败或返回空音频
     */
    @Override
    public byte[] synthesize(SpeechRequest request, String text) {
        assertProtocolSupported(request);
        if (nativeDashScope(request)) {
            return dashScopeNativeSynthesize(request, text);
        }
        return compatibleSynthesize(request, text);
    }

    /**
     * 语音连通测试。识别用一段极短的静音 WAV，合成用两个字；
     * 2xx 即证明「鉴权 + 模型 + 语音端点」都通，不额外解析业务内容。
     *
     * <p>为什么识别探活用静音而不是真实语音：探活只需验证链路，不该为了测一次连通
     * 让用户先录一段话；静音在多数实现里返回 200 与空文本，正好被本方法按「通」处理。
     * DashScope 原生合成会多下载一次音频 URL，这仍然只验证链路，不会朗读长文本。
     *
     * @param request 出站参数
     * @return 探活结果；失败文案已脱敏
     */
    public ProbeResult probe(SpeechRequest request) {
        long started = System.nanoTime();
        try {
            assertProtocolSupported(request);
            boolean nativeDashScope = nativeDashScope(request);
            if (request.capability() == ModelCapability.ASR) {
                if (nativeDashScope) {
                    dashScopeNativeTranscribeQuietly(request);
                } else {
                    compatibleTranscribeQuietly(request);
                }
            } else {
                synthesize(request, "你好");
            }
            String message = nativeDashScope
                    ? "语音接口连通（DashScope 原生）"
                    : "语音接口连通";
            return new ProbeResult(true, elapsed(started), message);
        } catch (BizException ex) {
            return new ProbeResult(false, elapsed(started), ex.getMessage());
        } catch (RuntimeException ex) {
            // 未预期异常不把类名/堆栈抛给前端；日志里由连通测试的调用方按成功/失败记录
            return new ProbeResult(false, elapsed(started), "语音接口连通测试失败，请查看服务端日志");
        }
    }

    /**
     * 兼容形态识别：multipart 上传整段音频。
     *
     * @param request  出站参数
     * @param audio    音频字节
     * @param fileName 上传文件名
     * @param mimeType 音频 MIME
     * @return 识别文字
     */
    private String compatibleTranscribe(SpeechRequest request, byte[] audio, String fileName, String mimeType) {
        Map<String, String> fields = new LinkedHashMap<>();
        // 只发 model：response_format 并非所有兼容实现都认，缺省本就是 JSON，少一个字段少一次 400
        fields.put("model", request.modelId());
        String body = http.postMultipart(http.join(request.baseUrl(), "/audio/transcriptions"),
                request.apiKey(), fields, fileName, mimeType, audio);
        String text = textOf(body);
        if (text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有识别到有效语音，请靠近麦克风再说一次");
        }
        return text;
    }

    /**
     * 识别探活的兼容形态入口：静音 WAV 可能被上游正常返回空文本，这里不能按「空结果」判失败。
     *
     * @param request 出站参数
     */
    private void compatibleTranscribeQuietly(SpeechRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", request.modelId());
        http.postMultipart(http.join(request.baseUrl(), "/audio/transcriptions"),
                request.apiKey(), fields, "probe.wav", "audio/wav", SpeechProbeAudio.silentWav());
    }

    /**
     * 附加参数合并闸门：只对 TTS 生效，且只浅合并一层。
     *
     * <p>为什么在 infra 合并而不是在调用方：两条协议的可选参数位置不同
     * （兼容口在请求体顶层、DashScope 原生在 {@code input} 内），
     * 但「哪些键不许覆盖」的规则只有一套，放在出站最后一刻最不容易漏。
     *
     * @param request 出站参数（含已解析的附加参数）
     * @param target  该协议 TTS 报文的可选参数段（按引用写入）
     */
    private void mergeExtraParams(SpeechRequest request, Map<String, Object> target) {
        if (request.capability() != ModelCapability.TTS) {
            // ASR 即便历史数据里带了附加参数也不合并，避免把合成参数塞进识别请求
            return;
        }
        SpeechParams.mergeInto(target, request.extraParams());
    }

    /**
     * 兼容形态合成：JSON 进、音频字节出。
     *
     * @param request 出站参数
     * @param text    待朗读文字
     * @return 音频字节
     */
    private byte[] compatibleSynthesize(SpeechRequest request, String text) {
        String format = request.format() == null || request.format().isBlank()
                ? SpeechOptions.DEFAULT_FORMAT : request.format();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.modelId());
        payload.put("input", text);
        payload.put("voice", compatibleVoice(request));
        payload.put("response_format", format);
        // 附加参数：兼容口（OpenAI /audio/speech）的可选参数就在请求体顶层，键名原样合并。
        // 系统键 model / input / file 与凭证类键由 SpeechParams 挡掉，用户填 voice 覆盖上面默认音色
        mergeExtraParams(request, payload);
        byte[] audio = http.postJsonForBytes(http.join(request.baseUrl(), "/audio/speech"),
                request.apiKey(), jsonOf(payload));
        if (audio.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音合成没有返回音频，请稍后重试");
        }
        return audio;
    }

    /**
     * DashScope 原生识别：qwen3-asr-flash 同步口，音频以 data URL 放进 messages.content。
     *
     * @param request  出站参数
     * @param audio    音频字节
     * @param mimeType 音频 MIME（决定 data URL 的 media type）
     * @return 识别文字
     */
    private String dashScopeNativeTranscribe(SpeechRequest request, byte[] audio, String mimeType) {
        String body = dashScopeNativeTranscribeBody(request, audio, mimeType);
        String response = http.postJsonForText(dashScopeNativeTranscribeUrl(request),
                request.apiKey(), body);
        String text = nativeAsrTextOf(response);
        if (text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有识别到有效语音，请靠近麦克风再说一次");
        }
        return text;
    }

    /**
     * 识别探活的原生形态入口：只要求 2xx，不因静音返回空文本而判失败。
     *
     * @param request 出站参数
     */
    private void dashScopeNativeTranscribeQuietly(SpeechRequest request) {
        String body = dashScopeNativeTranscribeBody(request, SpeechProbeAudio.silentWav(), "audio/wav");
        http.postJsonForText(dashScopeNativeTranscribeUrl(request), request.apiKey(), body);
    }

    /**
     * 组装原生识别的 JSON：model + input.messages[].content[].audio（data URL）+ asr_options。
     *
     * @param request  出站参数
     * @param audio    音频字节
     * @param mimeType 音频 MIME
     * @return JSON 文本
     */
    private String dashScopeNativeTranscribeBody(SpeechRequest request, byte[] audio, String mimeType) {
        String dataUrl = "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(audio);
        Map<String, Object> content = Map.of("audio", dataUrl);
        Map<String, Object> message = Map.of(
                "role", "user",
                "content", List.of(content));
        Map<String, Object> asrOptions = new LinkedHashMap<>();
        // 探活与对话同一口径：不做逆文本归一化，减少与识别结果无关的差异
        asrOptions.put("enable_itn", false);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("asr_options", asrOptions);
        // 原生口默认可能回 text；显式要 message 才能稳定读到 content[].text
        parameters.put("result_format", "message");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.modelId());
        body.put("input", Map.of("messages", List.of(message)));
        body.put("parameters", parameters);
        return jsonOf(body);
    }

    /**
     * @param request 出站参数
     * @return 原生识别完整 URL
     */
    private String dashScopeNativeTranscribeUrl(SpeechRequest request) {
        return DashScopeNativePaths.join(request.baseUrl(), DashScopeNativePaths.MULTIMODAL_GENERATION);
    }

    /**
     * DashScope 原生合成：SpeechSynthesizer 返回 audio.data（base64）或 audio.url，统一转成字节。
     *
     * @param request 出站参数
     * @param text    待朗读文字
     * @return 音频字节
     */
    private byte[] dashScopeNativeSynthesize(SpeechRequest request, String text) {
        String format = dashScopeNativeFormat(request);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", dashScopeNativeVoice(request));
        input.put("format", format);
        input.put("sample_rate", NATIVE_SAMPLE_RATE);
        // 附加参数：原生 SpeechSynthesizer 的可选参数（voice / format / sample_rate / instruction …）
        // 都在 input 下，因此合并目标是 input；input.text 与顶层 model 由平台保留
        mergeExtraParams(request, input);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.modelId());
        body.put("input", input);
        String url = DashScopeNativePaths.join(request.baseUrl(), DashScopeNativePaths.SPEECH_SYNTHESIZER);
        String response = http.postJsonForText(url, request.apiKey(), jsonOf(body));
        byte[] audio = nativeAudioBytes(response, request.apiKey());
        if (audio.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音合成没有返回音频，请稍后重试");
        }
        return audio;
    }

    /**
     * 解析原生合成响应：优先 audio.data（base64），没有再下载 audio.url。
     *
     * @param response 上游 JSON
     * @param apiKey   脱敏用 Key
     * @return 音频字节
     */
    private byte[] nativeAudioBytes(String response, String apiKey) {
        JsonNode root;
        try {
            root = JSON.readTree(response);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音合成返回不是合法 JSON");
        }
        JsonNode audioNode = root.path("output").path("audio");
        String data = audioNode.path("data").asText("");
        if (!data.isBlank()) {
            try {
                // MIME 解码器容忍换行：部分实现会把 base64 按行折开
                return Base64.getMimeDecoder().decode(data);
            } catch (IllegalArgumentException ex) {
                throw new BizException(ErrorCode.BAD_REQUEST, "语音合成返回的音频数据不合法");
            }
        }
        String downloadUrl = audioNode.path("url").asText("");
        if (downloadUrl.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音合成没有返回音频地址，请稍后重试");
        }
        // 临时 URL 自带签名，不再附带 Key；失败文案由 SpeechHttpSupport 脱敏
        return http.getBytes(downloadUrl, apiKey);
    }

    /**
     * 兼容形态合成音色：用户显式配置优先；未配置时按协议给默认值。
     *
     * <p>OpenAI 兼容沿用 alloy；DashScope 兼容路的 qwen-tts 系列不认 alloy，
     * 默认用官方 Cherry，否则连通测试会在音色校验处 400。
     *
     * @param request 出站参数
     * @return 音色 id
     */
    private String compatibleVoice(SpeechRequest request) {
        String configured = request.voice() == null ? "" : request.voice().trim();
        if (!configured.isBlank()) {
            return configured;
        }
        return request.protocol() == ModelProtocol.DASHSCOPE
                ? DASHSCOPE_COMPATIBLE_DEFAULT_VOICE
                : SpeechOptions.DEFAULT_VOICE;
    }

    /**
     * 原生合成音色：用户显式配置优先；未配置（或仍是 OpenAI 默认 alloy）时按模型族给官方示例音色。
     *
     * @param request 出站参数
     * @return 音色 id
     */
    private String dashScopeNativeVoice(SpeechRequest request) {
        String configured = request.voice() == null ? "" : request.voice().trim();
        if (!configured.isBlank() && !SpeechOptions.DEFAULT_VOICE.equalsIgnoreCase(configured)) {
            return configured;
        }
        String model = request.modelId() == null ? "" : request.modelId().toLowerCase(Locale.ROOT);
        if (model.contains("qwen-audio")) {
            return "longanhuan_v3.1";
        }
        if (model.contains("cosyvoice-v2")) {
            return "longxiaochun_v2";
        }
        return "longxiaochun";
    }

    /**
     * 原生合成格式：白名单外的 aac / flac 只有兼容口支持，明确报错而不是偷偷换成 mp3。
     *
     * @param request 出站参数
     * @return 规范小写格式
     */
    private String dashScopeNativeFormat(SpeechRequest request) {
        String format = request.format() == null || request.format().isBlank()
                ? SpeechOptions.DEFAULT_FORMAT : request.format().toLowerCase(Locale.ROOT);
        if (!NATIVE_FORMATS.contains(format)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "DashScope 原生语音合成不支持 " + format + " 格式，请改用 mp3 / wav / opus / pcm");
        }
        return format;
    }

    /**
     * 只有 OpenAI 兼容与 DashScope 有语音端点；其余协议明确「该协议不支持此用途」。
     * 矩阵通常已先拦截，这里再兜一层，避免未来矩阵放行后少做校验。
     *
     * @param request 出站参数
     * @throws BizException 协议不支持
     */
    private void assertProtocolSupported(SpeechRequest request) {
        if (request.protocol() == ModelProtocol.OPENAI_COMPAT
                || request.protocol() == ModelProtocol.DASHSCOPE) {
            return;
        }
        String purpose = request.capability() == null ? "语音" : request.capability().name();
        throw new BizException(ErrorCode.BAD_REQUEST,
                "用途未适配：" + request.protocol() + "×" + purpose + "（该协议不支持此用途），未提供语音端点");
    }

    /**
     * @param request 出站参数
     * @return DashScope 且不是 compatible-mode 基址时为原生形态
     */
    private boolean nativeDashScope(SpeechRequest request) {
        return request.protocol() == ModelProtocol.DASHSCOPE
                && !isCompatibleMode(request.baseUrl());
    }

    /**
     * @param baseUrl 供应商地址
     * @return 是否 compatible-mode 基址
     */
    private boolean isCompatibleMode(String baseUrl) {
        return baseUrl != null && baseUrl.contains(COMPATIBLE_MODE_HINT);
    }

    /**
     * 从兼容形态识别响应里取 text。兼容两种返回：标准 JSON {@code {"text":"…"}}，以及个别中转的纯文本。
     *
     * @param body 响应正文
     * @return 去空白后的文字；取不到为空串
     */
    private String textOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode text = root == null ? null : root.get("text");
            if (text != null && text.isTextual()) {
                return text.asText().trim();
            }
        } catch (Exception ignored) {
            // 不是 JSON 就按纯文本处理，交给上层判断是否为空
        }
        // 纯文本兜底：去掉可能的引号与 BOM，避免把包装字符填进输入框
        return body.replace("\uFEFF", "").trim().replaceAll("^\"|\"$", "");
    }

    /**
     * 从 DashScope 原生识别响应里取文字：
     * {@code output.choices[0].message.content[0].text}，兼容 content 为字符串或 output.text。
     *
     * @param body 响应正文
     * @return 去空白后的文字；取不到为空串
     */
    private String nativeAsrTextOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode content = root.path("output").path("choices").path(0).path("message").path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    JsonNode text = item.get("text");
                    if (text != null && text.isTextual() && !text.asText().isBlank()) {
                        return text.asText().trim();
                    }
                    if (item.isTextual() && !item.asText().isBlank()) {
                        return item.asText().trim();
                    }
                }
            }
            if (content.isTextual() && !content.asText().isBlank()) {
                return content.asText().trim();
            }
            JsonNode text = root.path("output").path("text");
            if (text.isTextual() && !text.asText().isBlank()) {
                return text.asText().trim();
            }
        } catch (Exception ignored) {
            // 结构不合法按「没有识别结果」交给上层给业务文案，不把解析异常抛给前端
        }
        return "";
    }

    /**
     * @param value 任意对象
     * @return JSON 文本
     * @throws BizException 序列化失败
     */
    private String jsonOf(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            // 载荷里全是字符串/Map，理论上不会失败；兜底不把异常类型泄给前端
            throw new BizException(ErrorCode.BAD_REQUEST, "语音请求组装失败，请重试");
        }
    }

    /**
     * @param started 起始纳秒
     * @return 毫秒耗时
     */
    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * 识别探活用的极短静音 WAV。每次探活现取，避免共享可变字节数组。
     *
     * @return 0.5 秒 16kHz 单声道 16bit 静音 WAV
     */
    static final class SpeechProbeAudio {

        private static final int SAMPLE_RATE = 16000;
        private static final int SECONDS_HALF = SAMPLE_RATE / 2;

        private SpeechProbeAudio() {
        }

        static byte[] silentWav() {
            int dataSize = SECONDS_HALF * 2;
            byte[] wav = new byte[44 + dataSize];
            writeAscii(wav, 0, "RIFF");
            writeInt(wav, 4, 36 + dataSize);
            writeAscii(wav, 8, "WAVE");
            writeAscii(wav, 12, "fmt ");
            writeInt(wav, 16, 16);
            writeShort(wav, 20, 1);
            writeShort(wav, 22, 1);
            writeInt(wav, 24, SAMPLE_RATE);
            writeInt(wav, 28, SAMPLE_RATE * 2);
            writeShort(wav, 32, 2);
            writeShort(wav, 34, 16);
            writeAscii(wav, 36, "data");
            writeInt(wav, 40, dataSize);
            // 采样区全 0 即静音，无需再填
            return wav;
        }

        private static void writeAscii(byte[] target, int offset, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, target, offset, bytes.length);
        }

        private static void writeInt(byte[] target, int offset, int value) {
            target[offset] = (byte) (value & 0xFF);
            target[offset + 1] = (byte) ((value >> 8) & 0xFF);
            target[offset + 2] = (byte) ((value >> 16) & 0xFF);
            target[offset + 3] = (byte) ((value >> 24) & 0xFF);
        }

        private static void writeShort(byte[] target, int offset, int value) {
            target[offset] = (byte) (value & 0xFF);
            target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        }
    }
}
