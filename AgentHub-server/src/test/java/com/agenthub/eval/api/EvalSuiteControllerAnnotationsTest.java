package com.agenthub.eval.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制器权限注解守门。所属域：eval。
 * <p>为什么用反射测：管理 API 漏挂注解不会编译失败，只会在运行时静默放开。
 * 这层断言不连库、不起服务，成本极低。
 */
class EvalSuiteControllerAnnotationsTest {

    @Test
    void everyEndpointCarriesPermissionCode() {
        Method[] methods = EvalSuiteController.class.getDeclaredMethods();
        int checked = 0;
        for (Method method : methods) {
            SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
            if (annotation == null && method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class) == null) {
                continue;
            }
            assertNotNull(annotation, "接口缺少权限注解：" + method.getName());
            String[] values = annotation.value();
            assertEquals(1, values.length, "本域每个接口只对应一个权限码：" + method.getName());
            assertTrue(values[0].equals(EvalPermissions.SUITE_VIEW)
                            || values[0].equals(EvalPermissions.SUITE_EDIT),
                    "权限码必须是本域常量：" + method.getName() + " -> " + values[0]);
            checked++;
        }
        // 9 个端点：列表/详情/建/改/导入/样例导入/用例列表/写用例/删用例/删套件 → 至少 9
        assertTrue(checked >= 9, "端点数量异常：" + checked);
    }

    @Test
    void permissionCodesFollowDomainFormat() {
        assertEquals("eval:suite:view", EvalPermissions.SUITE_VIEW);
        assertEquals("eval:suite:edit", EvalPermissions.SUITE_EDIT);
    }
}
