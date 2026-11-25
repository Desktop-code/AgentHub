package com.agenthub.eval.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.domain.model.EvalSuiteSpec;
import com.agenthub.eval.infra.EvalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台样例 classpath 装载。所属域：eval。
 * <p>成功读 main 资源；缺失 / 空配置路径失败文案可读；绝不发明 cases。
 */
class EvalSampleCatalogTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void requirePlatformSampleReadsClasspathJson() {
        EvalProperties properties = new EvalProperties();
        EvalSampleCatalog catalog = new EvalSampleCatalog(new DefaultResourceLoader(), json, properties);
        EvalSuiteSpec spec = catalog.requirePlatformSample();
        assertEquals("platform.smoke.rag", spec.getSuiteKey());
        assertEquals(5, spec.getCases().size());
        assertEquals(EvalSampleCatalog.DEFAULT_LOCATION, catalog.resolveLocation());
    }

    @Test
    void missingResourceThrowsWithPath() {
        EvalProperties properties = new EvalProperties();
        properties.getSamples().setLocation("classpath:eval/samples/__missing__.json");
        EvalSampleCatalog catalog = new EvalSampleCatalog(new DefaultResourceLoader(), json, properties);
        BizException ex = assertThrows(BizException.class, catalog::requirePlatformSample);
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("平台样例资源缺失"));
        assertTrue(ex.getMessage().contains("eval/samples/__missing__.json"));
    }
}
