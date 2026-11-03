package com.agenthub.eval.domain.model;

import lombok.Data;

/**
 * 单条规则断言结果。所属域：eval 的 domain。
 * <p>写入 eval_result.assertion_details_json 数组元素；纯数据，无副作用。
 */
@Data
public class AssertionResult {

    /** 断言类型，如 final_contains */
    private String type;
    /** 是否 blocking 门禁（false = warning，只进明细） */
    private boolean blocking;
    /** 本条是否通过 */
    private boolean ok;
    /** 人话说明（失败原因 / 通过摘要） */
    private String message;
    /** 期望摘要，可空 */
    private String expected;
    /** 实际摘要，可空 */
    private String actual;

    /**
     * @param type     类型
     * @param blocking 是否门禁
     * @param ok       是否通过
     * @param message  说明
     * @return 结果
     */
    public static AssertionResult of(String type, boolean blocking, boolean ok, String message) {
        AssertionResult one = new AssertionResult();
        one.setType(type);
        one.setBlocking(blocking);
        one.setOk(ok);
        one.setMessage(message);
        return one;
    }

    /**
     * @param type     类型
     * @param blocking 是否门禁
     * @param ok       是否通过
     * @param message  说明
     * @param expected 期望
     * @param actual   实际
     * @return 结果
     */
    public static AssertionResult of(String type, boolean blocking, boolean ok, String message,
                                    String expected, String actual) {
        AssertionResult one = of(type, blocking, ok, message);
        one.setExpected(expected);
        one.setActual(actual);
        return one;
    }
}
