package com.agenthub.runtime.api.dto;

import com.agenthub.runtime.domain.service.UserMessageFiles;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮对话的用户输入。所属域：runtime。正文、图片和文件都不进操作日志。
 *
 * <p>图片以「data:{mime};base64,…」或 http(s) 地址给出，文件以文件名加 data URI 给出。
 * 可以只发图、只发文件，或图、文件和文字一起发。正文为空时必须至少有一张图片或一个文件。
 * 图片数量与体积的业务上限在 {@code VisionOcrTexts}，文件在 {@code UserMessageFiles}。
 * 非图片文件不进视觉模型。
 */
@Data
public class MessageReq {

    /** 可空：只发图时为空串。有文字时不超过 8000 字。 */
    @Size(max = 8000, message = "消息不能超过 8000 字")
    private String content;

    /**
     * 随本轮一起提交的图片。空表示纯文本提问。
     * 上限 4 张与 {@code VisionOcrTexts.MAX_IMAGES} 一致：图片是 token 大户，
     * 不设限会让一次运行直接烧掉模型配额。
     */
    @Size(max = 4, message = "一次最多上传 4 张图片")
    private List<String> images;

    /**
     * 随本轮一起提交的非图片文件。与图片进同一条消息，不另开上传接口。
     * 上限 4 个与 {@code UserMessageFiles.MAX_FILES} 一致。
     */
    @Valid
    @Size(max = 4, message = "一次最多上传 4 个文件")
    private List<UserFilePart> files;

    /**
     * 图和文件的添加顺序，元素只能是 {@code image} 或 {@code file}。
     * 不传时按图片在前、文件在后。纯图片不需要这个字段，仍走 ahimg。
     */
    @Size(max = 8, message = "附件过多")
    private List<String> attachmentOrder;

    /**
     * 正文、图片、文件至少有一边。只发图或只发文件时 content 允许空白。
     *
     * @return 能进入本轮则为 true
     */
    @JsonIgnore
    @AssertTrue(message = "消息不能为空")
    public boolean isContentOrImagesPresent() {
        if (content != null && !content.isBlank()) {
            return true;
        }
        if (images != null) {
            for (String image : images) {
                if (image != null && !image.isBlank()) {
                    return true;
                }
            }
        }
        if (files != null) {
            for (UserFilePart file : files) {
                if (file != null && file.getDataUrl() != null && !file.getDataUrl().isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 交给运行时的文件列表。空表示这轮没有非图片文件。
     *
     * @return 文件名和 data URI，顺序与请求一致
     */
    @JsonIgnore
    public List<UserMessageFiles.NamedPayload> filePayloads() {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<UserMessageFiles.NamedPayload> payloads = new ArrayList<>();
        for (UserFilePart file : files) {
            if (file == null) {
                continue;
            }
            payloads.add(new UserMessageFiles.NamedPayload(file.getName(), file.getDataUrl()));
        }
        return payloads;
    }

    /**
     * 去掉正文首尾空白。未传正文时当作空串，避免只发图时对 null 调用 trim。
     *
     * @return 可能为空的正文
     */
    @JsonIgnore
    public String trimmedContent() {
        return content == null ? "" : content.trim();
    }

    /**
     * 一个非图片附件。所属域：runtime。体积在落盘时按解码字节校验，不信这里的声明。
     */
    @Data
    public static class UserFilePart {

        /** 展示名。路径会被丢掉，只留最后一段。 */
        @NotBlank(message = "文件名不能为空")
        @Size(max = 180, message = "文件名过长")
        private String name;

        /** {@code data:...;base64,...}。不接受 http 外链，避免服务端代下载。 */
        @NotBlank(message = "文件内容不能为空")
        private String dataUrl;
    }
}
