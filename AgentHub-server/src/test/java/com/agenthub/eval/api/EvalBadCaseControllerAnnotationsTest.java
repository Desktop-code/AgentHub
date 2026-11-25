package com.agenthub.eval.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坏案列表与回流接口权限注解守门。所属域：eval。
 * <p>列表用 RUN_VIEW；promote 用 SUITE_EDIT；禁 SSE / 假完成端点。
 */
class EvalBadCaseControllerAnnotationsTest {

    private static final Set<String> ALLOWED = Set.of(
            EvalPermissions.RUN_VIEW, EvalPermissions.SUITE_EDIT);

    /**
     * 每个映射方法必须挂且只挂一个允许的权限码。
     */
    @Test
    void everyEndpointCarriesPermissionCode() {
        int checked = 0;
        for (Method method : EvalBadCaseController.class.getDeclaredMethods()) {
            boolean isEndpoint = method.getAnnotation(GetMapping.class) != null
                    || method.getAnnotation(PostMapping.class) != null
                    || method.getAnnotation(PutMapping.class) != null
                    || method.getAnnotation(DeleteMapping.class) != null;
            if (!isEndpoint) {
                continue;
            }
            SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
            assertNotNull(annotation, "接口缺少权限注解：" + method.getName());
            String[] values = annotation.value();
            assertEquals(1, values.length, "每个接口只对应一个权限码：" + method.getName());
            assertTrue(ALLOWED.contains(values[0]),
                    "权限码越界：" + method.getName() + " -> " + values[0]);
            checked++;
        }
        // pageResults / fromResult / fromTrace
        assertEquals(3, checked, "端点数量异常：" + checked);
    }

    /**
     * promote 必须是 suite:edit；列表必须是 run:view。
     */
    @Test
    void promoteUsesSuiteEditAndListUsesRunView() throws NoSuchMethodException {
        SaCheckPermission list = EvalBadCaseController.class
                .getDeclaredMethod("pageResults", com.agenthub.eval.api.dto.EvalBadCasePageReq.class)
                .getAnnotation(SaCheckPermission.class);
        assertEquals(EvalPermissions.RUN_VIEW, list.value()[0]);

        SaCheckPermission fromResult = EvalBadCaseController.class
                .getDeclaredMethod("fromResult", Long.class, com.agenthub.eval.api.dto.EvalPromoteFromResultReq.class)
                .getAnnotation(SaCheckPermission.class);
        assertEquals(EvalPermissions.SUITE_EDIT, fromResult.value()[0]);

        SaCheckPermission fromTrace = EvalBadCaseController.class
                .getDeclaredMethod("fromTrace", Long.class, com.agenthub.eval.api.dto.EvalPromoteFromTraceReq.class)
                .getAnnotation(SaCheckPermission.class);
        assertEquals(EvalPermissions.SUITE_EDIT, fromTrace.value()[0]);
    }
}
