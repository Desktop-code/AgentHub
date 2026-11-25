package com.agenthub.eval.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * EvalScope 桥接口权限注解守门。所属域：eval。
 * <p>仅 export 一端点，权限复用 {@link EvalPermissions#RUN_TRIGGER}。
 */
class EvalScopeBridgeControllerAnnotationsTest {

    @Test
    void exportUsesRunTriggerPermission() throws NoSuchMethodException {
        Method method = EvalScopeBridgeController.class.getDeclaredMethod(
                "export", Long.class, com.agenthub.eval.api.dto.EvalScopeExportReq.class);
        assertNotNull(method.getAnnotation(PostMapping.class));
        SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);
        assertNotNull(permission);
        assertEquals(1, permission.value().length);
        assertEquals(EvalPermissions.RUN_TRIGGER, permission.value()[0]);
    }
}
