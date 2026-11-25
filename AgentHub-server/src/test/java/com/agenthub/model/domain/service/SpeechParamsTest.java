package com.agenthub.model.domain.service;

import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附加参数保存校验与出站合并：空合法、根必须对象、体积上限、凭证 / 保留键拦截、浅合并保键名。
 * 本文件只在本机执行（src/test 不入库）。
 */
class SpeechParamsTest {

    @Test
    void emptyOrMissingIsValid() {
        SpeechParams.validate(null);
        SpeechParams.validate("");
        SpeechParams.validate("{\"temperature\":0}");
        SpeechParams.validate("{\"speechParams\":null}");
        SpeechParams.validate("{\"speechParams\":{}}");
    }

    @Test
    void objectRootIsAccepted() {
        SpeechParams.validate("{\"speechParams\":{\"voice\":\"longanhuan_v3.1\",\"speed\":1.1}}");
    }

    @Test
    void arrayOrScalarRootIsRejected() {
        BizException array = assertThrows(BizException.class,
                () -> SpeechParams.validate("{\"speechParams\":[1,2]}"));
        assertTrue(array.getMessage().contains("必须是 JSON 对象"), array.getMessage());

        BizException text = assertThrows(BizException.class,
                () -> SpeechParams.validate("{\"speechParams\":\"voice\"}"));
        assertTrue(text.getMessage().contains("必须是 JSON 对象"), text.getMessage());
    }

    @Test
    void oversizedParamsAreRejected() {
        String big = "x".repeat(SpeechParams.MAX_BYTES);
        BizException ex = assertThrows(BizException.class,
                () -> SpeechParams.validate("{\"speechParams\":{\"instruction\":\"" + big + "\"}}"));
        assertTrue(ex.getMessage().contains("8KB"), ex.getMessage());
    }

    @Test
    void credentialKeysAreRejected() {
        for (String key : new String[]{"api_key", "apiKey", "Authorization", "X-Api-Key", "secret", "access_token"}) {
            BizException ex = assertThrows(BizException.class, () -> SpeechParams.validate(
                    "{\"speechParams\":{\"" + key + "\":\"sk-x\"}}"), key);
            assertTrue(ex.getMessage().contains("凭证类键"), ex.getMessage());
        }
    }

    @Test
    void reservedKeysAreRejected() {
        for (String key : new String[]{"model", "input", "file", "text"}) {
            BizException ex = assertThrows(BizException.class, () -> SpeechParams.validate(
                    "{\"speechParams\":{\"" + key + "\":\"x\"}}"), key);
            assertTrue(ex.getMessage().contains("系统保留键"), ex.getMessage());
        }
    }

    @Test
    void controlCharactersAreRejected() {
        BizException ex = assertThrows(BizException.class, () -> SpeechParams.validate(
                "{\"speechParams\":{\"instruction\":\"a\\u0007b\"}}"));
        assertTrue(ex.getMessage().contains("控制字符"), ex.getMessage());
        // 换行 / 制表符属于正常文本，不拦
        SpeechParams.validate("{\"speechParams\":{\"instruction\":\"a\\nb\\tc\"}}");
    }

    @Test
    void parseReturnsEmptyWhenAbsent() {
        assertTrue(SpeechParams.parse(null).isEmpty());
        assertTrue(SpeechParams.parse("{}").isEmpty());
        assertTrue(SpeechParams.parse("{\"temperature\":0}").isEmpty());
    }

    @Test
    void parseDropsBlockedKeysEvenForLegacyRows() {
        Map<String, Object> parsed = SpeechParams.parse(
                "{\"speechParams\":{\"voice\":\"longwan\",\"apiKey\":\"sk-x\",\"model\":\"m\",\"speed\":1.2}}");
        assertEquals(2, parsed.size());
        assertEquals("longwan", parsed.get("voice"));
        assertEquals(1.2, parsed.get("speed"));
    }

    @Test
    void mergeKeepsKeyNamesAndNeverOverwritesReserved() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("model", "tts-1");
        target.put("input", "你好");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("voice", "cherry");
        params.put("model", "hacked");
        params.put("authorization", "Bearer x");

        SpeechParams.mergeInto(target, params);

        assertEquals("tts-1", target.get("model"));
        assertEquals("你好", target.get("input"));
        assertEquals("cherry", target.get("voice"));
        assertFalse(target.containsKey("authorization"));
    }

    @Test
    void blockedKeyDetectionIsCaseAndSeparatorInsensitive() {
        assertTrue(SpeechParams.isCredentialKey("API_KEY"));
        assertTrue(SpeechParams.isCredentialKey("x-api-key"));
        assertTrue(SpeechParams.isReservedKey("Model"));
        assertFalse(SpeechParams.isBlockedKey("instruction"));
        assertFalse(SpeechParams.isBlockedKey("voice"));
    }
}
