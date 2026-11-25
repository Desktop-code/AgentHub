package com.agenthub.runtime.api.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只发图时正文可以为空；文字和图片都没有则拒绝。
 */
class MessageReqTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void imageOnlyIsAccepted() {
        MessageReq req = new MessageReq();
        req.setContent("  ");
        req.setImages(List.of("data:image/png;base64,YQ=="));
        assertTrue(validator.validate(req).isEmpty());
        assertEquals("", req.trimmedContent());
    }

    @Test
    void fileOnlyIsAcceptedWithoutImages() {
        MessageReq req = new MessageReq();
        req.setContent("");
        MessageReq.UserFilePart file = new MessageReq.UserFilePart();
        file.setName("a.pdf");
        file.setDataUrl("data:application/pdf;base64,YQ==");
        req.setFiles(List.of(file));
        assertTrue(validator.validate(req).isEmpty());
        assertEquals("a.pdf", req.filePayloads().get(0).name());
    }

    @Test
    void blankWithoutImagesIsRejected() {
        MessageReq req = new MessageReq();
        req.setContent("   ");
        assertTrue(validator.validate(req).stream().anyMatch(item -> item.getMessage().contains("不能为空")));
    }
}
