package com.agenthub.model.infra.adapter;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.model.SpeechRequest;
import com.agenthub.model.domain.service.SpeechGateway;
import com.agenthub.model.domain.service.SpeechOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容语音出站。所属域：model 的 infra。
 *
 * <p>两条路径：
 * <ul>
 *   <li>识别：{@code POST {baseUrl}/audio/transcriptions}，multipart 传整段音频，回 {@code {"text": "…"}}；</li>
 *   <li>合成：{@code POST {baseUrl}/audio/speech}，JSON 传整段文字，回音频字节。</li>
 * </ul>
 *
 * <p>为什么只认这两个路径：语音有专用端点，拿 chat/completions 冒充只会返回文字，
 * 前端既播不出来、也识别不了，属于「假成功」。协议不支持时这里直接抛中文业务错，
 * 让前端（朗读）走浏览器降级、（听写）明确提示未绑定。
 *
 * <p>DashScope 先由矩阵放行，再在这里按 baseUrl 二次判定：只有 compatible-mode 基址才有
 * OpenAI 形态的语音端点；原生基址明确拒绝，不发一次必然 404 的请求。
 *
 * <p>线程：无状态可并发；出站是阻塞调用，调用方须在弹性线程池执行。
 */
@Component
public class SpeechClient implements SpeechGateway {

    /** DashScope 兼容模式基址特征；与试跑装配的判定保持一致 */
    private static final String COMPATIBLE_MODE_HINT = "compatible-mode";

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
     * @throws BizException 基址不支持、上游失败或结果为空
     */
    @Override
    public String transcribe(SpeechRequest request, byte[] audio, String fileName, String mimeType) {
        assertCompatibleBase(request);
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
     * 整段文字 → 音频。
     *
     * @param request 出站参数
     * @param text    待朗读文字，调用方已裁剪长度
     * @return 音频字节
     * @throws BizException 基址不支持、上游失败或返回空音频
     */
    @Override
    public byte[] synthesize(SpeechRequest request, String text) {
        assertCompatibleBase(request);
        String format = request.format() == null || request.format().isBlank()
                ? SpeechOptions.DEFAULT_FORMAT : request.format();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.modelId());
        payload.put("input", text);
        payload.put("voice", request.voice() == null || request.voice().isBlank()
                ? SpeechOptions.DEFAULT_VOICE : request.voice());
        payload.put("response_format", format);
        String body;
        try {
            body = JSON.writeValueAsString(payload);
        } catch (Exception ex) {
            // Map<String,Object> 里全是字符串，理论上不会失败；兜底不把异常类型泄给前端
            throw new BizException(ErrorCode.BAD_REQUEST, "语音合成请求组装失败，请重试");
        }
        byte[] audio = http.postJsonForBytes(http.join(request.baseUrl(), "/audio/speech"),
                request.apiKey(), body);
        if (audio.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音合成没有返回音频，请稍后重试");
        }
        return audio;
    }

    /**
     * 语音连通测试。识别用一段极短的静音 WAV，合成用两个字；
     * 2xx 即证明「鉴权 + 模型 + 语音端点」都通，不额外解析业务内容。
     *
     * <p>为什么识别探活用静音而不是真实语音：探活只需验证链路，不该为了测一次连通
     * 让用户先录一段话；静音在多数实现里返回 200 与空文本，正好被本方法按「通」处理。
     *
     * @param request 出站参数
     * @return 探活结果；失败文案已脱敏
     */
    public ProbeResult probe(SpeechRequest request) {
        long started = System.nanoTime();
        try {
            if (request.capability() == ModelCapability.ASR) {
                transcribeQuietly(request);
            } else {
                synthesize(request, "你好");
            }
            return new ProbeResult(true, elapsed(started), "语音接口连通");
        } catch (BizException ex) {
            return new ProbeResult(false, elapsed(started), ex.getMessage());
        } catch (RuntimeException ex) {
            // 未预期异常不把类名/堆栈抛给前端；日志里由连通测试的调用方按成功/失败记录
            return new ProbeResult(false, elapsed(started), "语音接口连通测试失败，请查看服务端日志");
        }
    }

    /**
     * 识别的探活入口：静音 WAV 可能被上游正常返回空文本，这里不能按「空结果」判失败。
     *
     * @param request 出站参数
     */
    private void transcribeQuietly(SpeechRequest request) {
        assertCompatibleBase(request);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", request.modelId());
        http.postMultipart(http.join(request.baseUrl(), "/audio/transcriptions"),
                request.apiKey(), fields, "probe.wav", "audio/wav", SpeechProbeAudio.silentWav());
    }

    /**
     * DashScope 只有 compatible-mode 基址才有 OpenAI 形态的语音端点。
     * 其余协议由矩阵把关，这里再兜一层，防止未来矩阵放行后少做校验。
     *
     * @param request 出站参数
     * @throws BizException 基址或协议不支持
     */
    private void assertCompatibleBase(SpeechRequest request) {
        if (request.protocol() == ModelProtocol.DASHSCOPE) {
            String baseUrl = request.baseUrl() == null ? "" : request.baseUrl();
            if (!baseUrl.contains(COMPATIBLE_MODE_HINT)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "语音接口仅支持 compatible-mode 基址（如 https://dashscope.aliyuncs.com/compatible-mode/v1）");
            }
        }
        if (request.protocol() == ModelProtocol.ANTHROPIC || request.protocol() == ModelProtocol.CUSTOM) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前协议不支持语音接口");
        }
    }

    /**
     * 从识别响应里取 text。兼容两种返回：标准 JSON {@code {"text":"…"}}，以及个别中转的纯文本。
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
