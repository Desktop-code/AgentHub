package com.agenthub.runtime.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.Result;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.api.dto.SpeechReq;
import com.agenthub.runtime.api.dto.VoiceTextResp;
import com.agenthub.runtime.application.VoiceIoAppService;
import com.agenthub.runtime.domain.model.SpeechAudio;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 对话页语音接口。所属域：runtime。
 *
 * <p>两条薄接口，与试跑同一套身份与权限：
 * <ul>
 *   <li>{@code POST /api/runtime/voice/transcribe}：整段音频 → 文字；</li>
 *   <li>{@code POST /api/runtime/voice/speak}：整段文字 → 音频字节。</li>
 * </ul>
 *
 * <p>为什么合成返回二进制而不是 {@code Result<T>}：响应体就是音频，
 * 套一层 JSON 等于让浏览器拿到 Base64 或一个错误对象，<audio> 播不出来。
 * 失败时仍由全局异常处理器返回标准 {@code Result}（此时尚未写出字节）。
 *
 * <p>权限口径：听写与试跑发送同级别（{@code runtime:trial:run}）；
 * 朗读本质是读回答，{@code runtime:trial:list} 或 run 任一即可。
 * 不新造权限码，避免菜单种子与注解再分叉。
 */
@RestController
@RequestMapping("/api/runtime/voice")
public class VoiceController {

    private final VoiceIoAppService voice;

    /**
     * @param voice 语音用例（取 Agent 绑定的 ASR / TTS 槽）
     */
    public VoiceController(VoiceIoAppService voice) {
        this.voice = voice;
    }

    /**
     * 整段音频 → 文字。识别成功后前端只把文字填进输入框，不自动发送。
     *
     * @param agentId 语音模型所属 Agent
     * @param file    录音文件（webm / wav / mp3 / m4a / ogg）
     * @return 识别文字
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<VoiceTextResp> transcribe(@RequestParam Long agentId,
                                            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有收到录音，请按住说话后再试");
        }
        byte[] audio;
        try {
            // 只读进内存：本刀不落盘、不进对象存储，用完随请求回收
            audio = file.getBytes();
        } catch (IOException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "读取录音失败，请重试");
        }
        VoiceTextResp resp = new VoiceTextResp();
        resp.setText(voice.transcribe(agentId, audio));
        return Result.ok(resp);
    }

    /**
     * 整段文字 → 音频。
     *
     * @param req 待朗读文字与 Agent
     * @return 音频字节；Content-Type 由合成格式决定
     */
    @SaCheckPermission(value = {RuntimePermissions.LIST, RuntimePermissions.RUN}, mode = SaMode.OR)
    @PostMapping("/speak")
    public ResponseEntity<byte[]> speak(@Valid @RequestBody SpeechReq req) {
        SpeechAudio audio = voice.synthesize(req.getAgentId(), req.getText());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.contentType()))
                // 语音是「本次回答的即时产物」：禁止中间层缓存，避免串听上一条内容
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(audio.bytes());
    }
}
