package com.agenthub.billing.api;

/**
 * Token 计费用权限码。所属域：billing。
 * 与 sql/data-init-billing-menu.sql、接口注解、前端按钮使用同一套码。本期没有作废码。
 */
public final class BillingPermissions {

    public static final String LEDGER_LIST = "billing:ledger:list";
    public static final String DAILY_LIST = "billing:daily:list";
    public static final String PRICE_LIST = "billing:price:list";
    public static final String PRICE_ADD = "billing:price:add";
    public static final String PRICE_EDIT = "billing:price:edit";
    public static final String PRICE_REMOVE = "billing:price:remove";
    public static final String PRICE_ENABLE = "billing:price:enable";

    private BillingPermissions() {
    }
}
