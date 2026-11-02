package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 试跑一轮的用户输入。所属域：runtime。正文与图片都不进操作日志。
 *
 * <p>图片以「data:{mime};base64,…」或 http(s) 地址给出，由前端读文件后转码。
 * 这里只做最外层数量与体积兜底；真正的业务校验（VISION 槽是否绑定）在
 * {@code TrialBindingLoader}，因为那里才知道当前 Agent 的配置。
 */
@Data
public class MessageReq {

    @NotBlank(message = "消息不能为空")
    @Size(max = 8000, message = "消息不能超过 8000 字")
    private String content;

    /**
     * 随本轮一起提交的图片。空表示纯文本提问。
     * 上限 4 张与 {@code VisionOcrTexts.MAX_IMAGES} 一致：图片是 token 大户，
     * 不设限会让一次试跑直接烧掉模型配额。
     */
    @Size(max = 4, message = "一次最多上传 4 张图片")
    private List<String> images;
}
