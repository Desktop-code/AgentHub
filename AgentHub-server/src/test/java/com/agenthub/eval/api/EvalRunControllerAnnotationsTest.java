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
 * 跑批接口权限注解守门。所属域：eval。
 * <p>刀4 已开放 report；仍禁止 fake-complete / SSE。
 */
class EvalRunControllerAnnotationsTest {

    private static final Set<String> ALLOWED = Set.of(
            EvalPermissions.RUN_TRIGGER, EvalPermissions.RUN_VIEW, EvalPermissions.RUN_CANCEL);

    @Test
    void everyEndpointCarriesPermissionCode() {
        int checked = 0;
        for (Method method : EvalRunController.class.getDeclaredMethods()) {
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
                    "权限码必须是跑批常量：" + method.getName() + " -> " + values[0]);
            checked++;
        }
        // 7 个端点：page / create / execute / get / results / report / cancel
        assertEquals(7, checked, "端点数量异常：" + checked);
    }

    @Test
    void permissionCodesFollowDomainFormat() {
        assertEquals("eval:run:trigger", EvalPermissions.RUN_TRIGGER);
        assertEquals("eval:run:view", EvalPermissions.RUN_VIEW);
        assertEquals("eval:run:cancel", EvalPermissions.RUN_CANCEL);
    }

    @Test
    void reportExistsWithViewPermissionAndNoSse() {
        boolean hasReport = false;
        for (Method method : EvalRunController.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase();
            if ("report".equals(name)) {
                hasReport = true;
                SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);
                assertNotNull(permission);
                assertEquals(EvalPermissions.RUN_VIEW, permission.value()[0]);
            }
            assertTrue(!name.contains("complete") && !name.contains("finish") && !name.contains("sse"),
                    "不得暴露伪造完成/SSE 端点：" + method.getName());
        }
        assertTrue(hasReport, "刀4 必须有 report 端点");
    }
}
